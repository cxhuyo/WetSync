package com.cxhuyo.wetsync;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信输入法「个人词库」→ 小布「个人词库」。
 *
 * 小布的 userDictSyncExport 产出的是明文 JSON：
 *   {"core_version":"v0.7.0-16","format_version":"3",
 *    "language_dict":{"cn_words":[{"word":"啊(a)","type":1,"timestamp":1792423165,
 *                                  "commits":3,"commits_by_spelling":{"quan_quan":3}}, ...],
 *                     "en_words":[...]}}
 *
 * 两点实测结论（决定了实现方式）：
 *  1. userDictSyncImport 是**合并**语义，不会删除文件里没有的词 —— 所以导入不会清掉用户已有词库。
 *  2. 但必须带上完整字段（尤其 en_words），只给 cn_words 会静默不生效；
 *     且导入后必须调 Kernel.restartForVocabulary()，否则引擎不重新装载。
 *
 * 因此流程固定为：导出全量 → 在 cn_words 数组末尾追加新词 → 导入 → 重启词库引擎。
 */
final class XiaobuDictSync {

    private static final Pattern WORD_RE =
            Pattern.compile("\"word\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 词条在 JSON 里的转义 */
    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 小布 word 字段形如「傻福(sha fu)」，取括号前的元词 */
    private static String baseWord(String w) {
        int i = w.lastIndexOf('(');
        return i > 0 ? w.substring(0, i) : w;
    }

    /**
     * 把微信词条合并进小布词库。
     *
     * @return 追加的条数；失败返回 -1（-2 表示导出失败）
     */
    /** 串行化 export → merge → import → restart 整条链路。
     *  并发（周期同步 + 页面手动同步）会让引擎在写入中途被 restart，实测会触发
     *  native 的 check_corrupt → res_repair → 把 ud.bin 重置（见 2026-10-28 03:45 事故）。 */
    private static final Object SYNC_LOCK = new Object();

    static int mergeInto(File dir, List<WxDictReader.Entry> wx) {
        return mergeInto(dir, wx, false);
    }

    private static int mergeInto(File dir, List<WxDictReader.Entry> wx, boolean afterRestore) {
        synchronized (SYNC_LOCK) {
            return mergeIntoLocked(dir, wx, afterRestore);
        }
    }

    /**
     * @param afterRestore 是否已经历过一次「塌库恢复」——恢复后要重新导出/解析，只允许重来一次
     */
    private static int mergeIntoLocked(File dir, List<WxDictReader.Entry> wx, boolean afterRestore) {
        if (wx == null || wx.isEmpty()) return 0;
        File full = new File(dir, "xb_dict_full.json");
        full.delete();
        if (!XiaobuPhraseStore.exportUserDict(full.getAbsolutePath())) {
            MainHookLogger.log("event=xbd_export_fail");
            return -2;
        }
        String json = readText(full);
        if (json == null || json.length() < 32) {
            MainHookLogger.log("event=xbd_read_fail");
            return -2;
        }

        // 现有词（元词 + 完整形式）+ 顺带收集「字 -> 宿主常用读音」，供多音字挑默认读音
        Set<String> haveBase = new LinkedHashSet<>();
        Set<String> haveFull = new LinkedHashSet<>();
        java.util.HashMap<String, String> preferPy = new java.util.HashMap<>();
        Matcher m = WORD_RE.matcher(json);
        while (m.find()) {
            String w = unescape(m.group(1));
            haveFull.add(w);
            String base = baseWord(w);
            haveBase.add(base);
            int lp = w.lastIndexOf('(');
            if (lp > 0 && w.endsWith(")")) {
                String py = w.substring(lp + 1, w.length() - 1).trim();
                String[] syl = py.split("\\s+");
                if (syl.length == base.length()) {
                    for (int i = 0; i < base.length(); i++) {
                        if (!preferPy.containsKey(String.valueOf(base.charAt(i)))) {
                            preferPy.put(String.valueOf(base.charAt(i)), syl[i].toLowerCase());
                        }
                    }
                }
            }
        }

        // 词库审计：跟上一轮导出的词表做差分，把「这一轮凭空少掉的词」点名记录下来。
        // 模块自身只做合并（import 是合并语义），从不删词 —— 所以一旦出现 removed>0，
        // 就是宿主引擎/其它写入方动的手。先留证据，再谈自动恢复。
        auditDiff(dir, haveFull);

        // 塌库检测必须放在「快照之前」：如果这一轮进来时词库已经被宿主引擎搞塌了，
        // 先拍快照会把上一份健康备份覆盖成废档，之后 restore 就等于恢复了个寂寞。
        if (!afterRestore && checkCollapse(dir, haveBase.size())) {
            // 已把健康快照写回并重启引擎，上面导出的 json 作废，重新走一轮
            return mergeInto(dir, wx, true);
        }
        snapshotUdBin(dir, haveBase.size());

        // 追加串：小布把中文词和英文词分开放（cn_words / en_words），
        // 放错数组引擎会直接丢掉（实测 adb/Magisk 这类纯英文词塞进 cn_words 后查不到）。
        StringBuilder addCn = new StringBuilder();
        StringBuilder addEn = new StringBuilder();
        StringBuilder sampleCn = new StringBuilder();
        StringBuilder sampleEn = new StringBuilder();
        int addedCn = 0, addedEn = 0, skipped = 0;
        int pyFixed = 0;
        java.util.ArrayList<String> addedWords = new java.util.ArrayList<>();
        for (WxDictReader.Entry e : wx) {
            if (e.word == null || e.word.isEmpty()) continue;
            if (haveBase.contains(e.word)) { skipped++; continue; }
            // 拼音必须能通过宿主「字->读音」校验：微信的拼音多音字/分词常与宿主不一致，
            // 宿主导入时逐条校验，对不上就静默丢弃（整体仍返回 true）。
            // 逐字校准：本来就合法的音节保留，对不上的换成宿主读音。
            String py = XiaobuPhraseStore.reconcilePinyin(e.word, e.pinyin, preferPy);
            if (py.isEmpty()) {
                py = e.pinyin;
            } else if (!py.equals(e.pinyin)) {
                pyFixed++;
            }
            String fullWord = e.word + "(" + py + ")";
            if (haveFull.contains(fullWord)) { skipped++; continue; }
            long ts = e.ts > 0 ? e.ts : System.currentTimeMillis() / 1000;
            boolean cjk = hasCjk(e.word);
            StringBuilder add = cjk ? addCn : addEn;
            if (add.length() > 0) add.append(',');
            if (cjk) {
                // 拼音可能为空（从微信 LevelDB 直接提取时拿不到完整拼音），
                // 空串会让 commits_by_spelling 的 key 变成 ""，退化成用词本身
                String spelled = py.isEmpty() ? e.word : py.replace(' ', '_');
                add.append("{\"word\":\"").append(esc(fullWord)).append("\",\"type\":1,\"timestamp\":")
                   .append(ts)
                   .append(",\"commits\":").append(e.commits)
                   .append(",\"commits_by_spelling\":{\"").append(esc(spelled)).append("\":")
                   .append(e.commits).append("}}");
                if (addedCn < 8) sampleCn.append('|').append(e.word);
                addedCn++;
            } else {
                // en_words 的词条没有 commits_by_spelling，且括号里放词本身
                add.append("{\"word\":\"").append(esc(e.word)).append('(').append(esc(e.word))
                   .append(")\",\"type\":1,\"timestamp\":").append(ts)
                   .append(",\"commits\":").append(e.commits).append('}');
                if (addedEn < 8) sampleEn.append('|').append(e.word);
                addedEn++;
            }
            haveBase.add(e.word);
            haveFull.add(fullWord);
            addedWords.add(e.word);
        }
        MainHookLogger.log("event=xbd_merge have=" + haveBase.size() + " wx=" + wx.size()
                + " addedCn=" + addedCn + " addedEn=" + addedEn + " skipped=" + skipped
                + " pyFixed=" + pyFixed
                + " sampleCn=" + sampleCn + " sampleEn=" + sampleEn);
        int added = addedCn + addedEn;
        if (added == 0) return 0;

        String merged = json;
        if (addCn.length() > 0) merged = insertIntoArray(merged, "cn_words", addCn.toString());
        if (addEn.length() > 0) merged = insertIntoArray(merged, "en_words", addEn.toString());
        if (merged == null) return -2;

        File out = new File(dir, "xb_dict_merge.json");
        try {
            FileOutputStream fo = new FileOutputStream(out, false);
            fo.write(merged.getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) {
            MainHookLogger.log("event=xbd_write_fail " + t);
            return -2;
        }
        if (!XiaobuPhraseStore.importUserDict(out.getAbsolutePath())) {
            MainHookLogger.log("event=xbd_import_fail");
            return -2;
        }
        // 给 native 引擎留出落盘时间，再重启词库引擎。
        // 旧实现 import 之后约 30ms 就 restartVocabulary()，若引擎此刻正在写 ud.bin，
        // 重启会让它走 check_corrupt → res_repair，把整库重置成几十条（2026-10-28 03:45 事故）。
        try { Thread.sleep(SETTLE_MS); } catch (InterruptedException ignore) {}
        XiaobuPhraseStore.restartVocabulary();
        MainHookLogger.log("event=xbd_import_ok added=" + added);
        verifyLanded(dir, addedWords);
        return added;
    }

    /**
     * 导入后回读校验：宿主的导入是「静默丢弃」语义，返回 true 不代表全部落地。
     * 这里重新导出一份，数一下本批词真正进了几条，把没进的打出来。
     */
    private static void verifyLanded(File dir, java.util.List<String> words) {
        if (words.isEmpty()) return;
        File chk = new File(dir, "xb_dict_verify.json");
        chk.delete();
        if (!XiaobuPhraseStore.exportUserDict(chk.getAbsolutePath())) {
            MainHookLogger.log("event=xbd_verify_export_fail");
            return;
        }
        String json = readText(chk);
        if (json == null || json.length() < 32) {
            MainHookLogger.log("event=xbd_verify_read_fail len="
                    + (json == null ? -1 : json.length()));
            return;
        }
        Set<String> now = new LinkedHashSet<>();
        Matcher m = WORD_RE.matcher(json);
        while (m.find()) now.add(baseWord(unescape(m.group(1))));
        StringBuilder miss = new StringBuilder();
        int landed = 0;
        for (String w : words) {
            if (now.contains(w)) landed++;
            else if (miss.length() < 160) miss.append('|').append(w);
        }
        int lost = words.size() - landed;
        MainHookLogger.log("event=xbd_verify batch=" + words.size() + " landed=" + landed
                + " lost=" + lost + " miss=" + miss);
    }

    /** import 之后等引擎落盘的时长（ms） */
    private static final long SETTLE_MS = 1500;

    /** 含中日韩统一表意文字就算中文词 */
    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /** 诊断用：暴露数组拼接，避免复制一份实现 */
    static String insertIntoArrayPub(String json, String key, String add) {
        return insertIntoArray(json, key, add);
    }

    /** 诊断用：暴露 JSON 字符串转义 */
    static String escPub(String s) {
        return esc(s);
    }

    /**
     * 词库总量看门狗。
     *
     * 现场见过 6 次「个人词库整体塌成几十条」：进程没重启、模块也没删词（导入是合并语义），
     * 说明是宿主引擎自己把 ud.bin 重建/修复掉了（libokim_shared 里有 res_repair + .backup 机制）。
     * 这里每轮同步把健康词库的导出 JSON 备份一份；一旦发现总量腰斩，就把它回灌并重启词库引擎。
     *
     * @return true 表示这一轮判定为「塌库」并已尝试恢复
     */
    static boolean checkCollapse(File dir, int now) {
        return checkCollapse(dir, now, -1L);
    }

    /**
     * @param used ud.bin 头部的「已用字节数」，&lt;0 表示没取到。它是一条独立于导出接口的物理判据：
     *             实测每词约 50~66 字节，若 used &lt; 词量×8 基本可以断定库已被 reset，
     *             这时连 userDictSyncExport 都可能失败（now&lt;=0），只看 now 会漏判。
     */
    static boolean checkCollapse(File dir, int now, long used) {
        // 基线取「上次健康时的词量」，落盘在快照目录里 —— 否则模块进程一重启基线就归零，
        // 恰好会漏掉「进程不在时塌的库」这种最需要救的场景。
        int base = knownCount(dir);
        boolean tooFew = now > 0 && base >= 200 && now * 2L <= base;
        boolean phys = used >= 0 && base >= 200 && used > 0 && used < base * 8L;
        if (!tooFew && !phys) {
            if (now > 0) lastKnownCount = Math.max(lastKnownCount, now);
            return false;
        }
        MainHookLogger.log("event=xbd_collapse last=" + base + " now=" + now + " used=" + used);

        // 第一优先：自己留的原始 ud.bin 滚动快照（格式已逆向，见 udbinUsed）。
        // 它保存的是引擎真正加载过的二进制状态，比 JSON 回灌完整（含词频/拼音），也不依赖
        // 那个「repair 之后静默不落地」的 import 接口。
        if (restoreFromRawSnapshot(dir, base)) {
            MainHookLogger.log("event=udbin_snap_restore_ok");
            return true;
        }

        if (restoreFromEngineBackup(dir, base)) {
            MainHookLogger.log("event=udbin_restore_ok");
            return true;
        }

        if (!restoreUdBin(dir)) {
            MainHookLogger.log("event=xbd_restore_fail");
            lastKnownCount = now;
            return false;
        }
        XiaobuPhraseStore.restartVocabulary();
        try { Thread.sleep(1500); } catch (InterruptedException ignore) {}
        File chk = new File(dir, "xb_dict_restored.json");
        chk.delete();
        int after = -1;
        if (XiaobuPhraseStore.exportUserDict(chk.getAbsolutePath())) {
            String s = readText(chk);
            after = s == null ? -1 : countWordKeys(s);
        }
        MainHookLogger.log("event=xbd_restore_done after=" + after);
        // 回灌没救回来（引擎已处于 repair/reset 后的「写不进」状态）：落一个标记，
        // 供下一轮 sync 或进程重启后再次恢复；2026-10-28 03:45 那次就是 after=31 却没留痕。
        if (base > 0 && after > 0 && after * 2L < base) {
            writeFlag(dir, "ud_recover.flag",
                    "base=" + base + " now=" + now + " after=" + after);
            MainHookLogger.log("event=xbd_restore_insufficient base=" + base + " after=" + after);
        }
        lastKnownCount = Math.max(after, now);
        return true;
    }

    /** 上次健康时的词量（内存优先，其次读快照目录里的 count.txt） */
    private static int knownCount(File dir) {
        if (lastKnownCount >= 0) return lastKnownCount;
        try {
            File f = new File(new File(dir, "ud_snapshot"), "count.txt");
            if (f.exists()) {
                int v = Integer.parseInt(readText(f).trim());
                if (v > 0) { lastKnownCount = v; return v; }
            }
        } catch (Throwable ignore) {}
        return lastKnownCount;
    }

    private static int lastKnownCount = -1;

    /**
     * 留一份「健康词库」备份。
     *
     * 备份的是**导出的 JSON**而不是 ud.bin —— ud.bin 正被 libokim_shared mmap 着，
     * 活体覆盖二进制库文件很容易把引擎彻底搞崩；JSON 回灌走的是官方
     * userDictSyncImport（合并语义），全程 API 层，安全且可验证。
     */
    private static void snapshotUdBin(File dir, int count) {
        snapshotFrom(dir, count, new File(dir, "xb_dict_full.json"));
    }

    /**
     * 用**指定的**导出文件刷新健康备份。
     *
     * 这里必须显式传来源：周期体检导出的是 `xb_dict_watch.json`，而 `xb_dict_full.json`
     * 只在「微信侧有新词」时才会重写。若体检直接拷 full，进程重启后 full 还是几小时前的旧文件，
     * 就会出现「count=14828 但备份内容只有 1111 词」的错位（2026-10-28 实测踩到）。
     */
    private static void snapshotFrom(File dir, int count, File src) {
        try {
            // 关键护栏：词量异常小的时候不许覆盖基线。
            // 塌库/空导出时如果照写，会把「唯一的好备份」和 count.txt 一起冲掉，之后就再也救不回来。
            if (count < 200) {
                MainHookLogger.log("event=xbd_snapshot_skip count=" + count);
                return;
            }
            File dst = new File(dir, "ud_snapshot");
            dst.mkdirs();
            File cntFile = new File(dst, "count.txt");
            int prev = -1;
            if (cntFile.exists()) {
                try { prev = Integer.parseInt(readText(cntFile).trim()); } catch (Throwable ignore) {}
            }
            // 只在「当前词量不比备份少」时才覆盖，避免把好备份换成更差的
            if (prev > count && count > 0) return;
            if (src == null || !src.exists() || src.length() < 32) return;
            // 自检：备份内容里的词条数必须与 count 同量级，否则说明拿错文件了
            int inFile = countWordKeys(readText(src));
            if (inFile < count / 2) {
                MainHookLogger.log("event=xbd_snapshot_mismatch count=" + count + " inFile=" + inFile);
                return;
            }
            copyFile(src, new File(dst, "xb_dict_healthy.json"));
            rotateSnapshot(dst, src);
            FileOutputStream fo = new FileOutputStream(cntFile, false);
            fo.write(String.valueOf(count).getBytes("UTF-8"));
            fo.close();
            lastKnownCount = Math.max(lastKnownCount, count);
            // 顺带留一份原始 ud.bin（只读拷贝，不动引擎 mmap）
            snapshotUdbinRaw(dir);
        } catch (Throwable t) {
            MainHookLogger.log("event=xbd_snapshot_fail " + t);
        }
    }

    /**
     * ud.bin 头部（逆向确认，2026-10-28）：
     *   u64 @0 = 文件/mmap 总长度
     *   u64 @8 = 已用数据字节数
     * 实测：13,777 词的健康库 used=910,720；被 reset 的库 used=6,056（只能存几十条）。
     * 所以 used 是最可靠的「词库是否塌了」的物理指标，可脱离导出接口独立判断。
     */
    private static long udbinUsed(File f) {
        if (f == null || !f.exists() || f.length() < 16) return -1;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] h = new byte[16];
            if (in.read(h) != 16) return -1;
            long used = 0;
            for (int i = 15; i >= 8; i--) used = (used << 8) | (h[i] & 0xFF);
            return used;
        } catch (Throwable t) {
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
        }
    }

    /** 从最近几份原始 ud.bin 快照里挑一份「明显比当前健康」的还原回去 */
    private static boolean restoreFromRawSnapshot(File dir, int base) {
        try {
            File res = new File(dir.getParentFile(), "resources");
            File cur = new File(res, "ud.bin");
            long curUsed = udbinUsed(cur);
            File snapDir = new File(dir, "ud_snapshot");
            File[] fs = snapDir.listFiles((d, n) -> n.startsWith("ud_bin_")
                    && n.endsWith(".bin") && !n.startsWith("ud_bin_broken_"));
            if (fs == null || fs.length == 0) return false;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : fs) {
                long used = udbinUsed(f);
                // 至少要能装下 base 词量的一半，且明显大于当前
                if (used < 200 * 1024) continue;
                if (curUsed > 0 && used < curUsed * 2) continue;
                MainHookLogger.log("event=udbin_snap_try snap=" + f.getName()
                        + " used=" + used + " curUsed=" + curUsed + " base=" + base);
                String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(new java.util.Date());
                File broken = new File(snapDir, "ud_bin_broken_" + ts + ".bin");
                try { if (cur.exists()) copyFile(cur, broken); } catch (Throwable ignore) {}
                File tmp = new File(res, "ud.bin.restore");
                copyFile(f, tmp);
                if (cur.exists() && !cur.delete()) return false;
                if (!tmp.renameTo(cur)) return false;
                XiaobuPhraseStore.restartVocabulary();
                try { Thread.sleep(2000); } catch (InterruptedException ignore) {}
                File chk = new File(dir, "xb_dict_restored.json");
                chk.delete();
                int after = -1;
                if (XiaobuPhraseStore.exportUserDict(chk.getAbsolutePath())) {
                    String js = readText(chk);
                    after = js == null ? -1 : countWordKeys(js);
                }
                MainHookLogger.log("event=udbin_snap_after n=" + after);
                if (after > 0 && after * 2 > base) {
                    lastKnownCount = Math.max(lastKnownCount, after);
                    return true;
                }
                try { copyFile(broken, cur); XiaobuPhraseStore.restartVocabulary(); } catch (Throwable ignore) {}
                return false;
            }
            return false;
        } catch (Throwable t) {
            MainHookLogger.log("event=udbin_snap_err " + t);
            return false;
        }
    }

    /**
     * 用引擎 repair 时留下的 `files/resources/ud.bin.backup` 还原词库。
     *
     * 触发条件（保守）：backup 存在、≥200 KB、且比当前 ud.bin 至少大一倍。
     * 还原方式：把 backup 写成 `ud.bin.restore`，再 rename 覆盖 `ud.bin`
     * —— rename 是原子替换，引擎当前 mmap 的旧 inode 不受影响，随后 restartVocabulary()
     * 才会打开新 inode，避免「边写边被 mmap」把引擎彻底搞崩。
     * 还原后导出计数，只有「恢复过半」才认定成功；否则把坏文件放回去并返回 false，交给 JSON 回灌。
     */
    private static boolean restoreFromEngineBackup(File dir, int base) {
        try {
            File res = new File(dir.getParentFile(), "resources");
            File cur = new File(res, "ud.bin");
            File bak = new File(res, "ud.bin.backup");
            if (!bak.exists() || bak.length() < 200 * 1024) {
                MainHookLogger.log("event=udbin_nobak exists=" + bak.exists()
                        + " size=" + (bak.exists() ? bak.length() : -1));
                return false;
            }
            long curLen = cur.exists() ? cur.length() : 0;
            if (bak.length() < curLen * 2) {
                MainHookLogger.log("event=udbin_bak_small bak=" + bak.length() + " cur=" + curLen);
                return false;
            }
            File snapDir = new File(dir, "ud_snapshot");
            snapDir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            File broken = new File(snapDir, "ud_bin_broken_" + ts + ".bin");
            try { if (cur.exists()) copyFile(cur, broken); } catch (Throwable ignore) {}

            MainHookLogger.log("event=udbin_restore_try bak=" + bak.length() + " cur=" + curLen
                    + " base=" + base);
            File tmp = new File(res, "ud.bin.restore");
            copyFile(bak, tmp);
            if (cur.exists() && !cur.delete()) {
                MainHookLogger.log("event=udbin_restore_del_fail");
                return false;
            }
            if (!tmp.renameTo(cur)) {
                MainHookLogger.log("event=udbin_restore_rename_fail");
                return false;
            }
            XiaobuPhraseStore.restartVocabulary();
            try { Thread.sleep(2000); } catch (InterruptedException ignore) {}

            File chk = new File(dir, "xb_dict_restored.json");
            chk.delete();
            int after = -1;
            if (XiaobuPhraseStore.exportUserDict(chk.getAbsolutePath())) {
                String js = readText(chk);
                after = js == null ? -1 : countWordKeys(js);
            }
            MainHookLogger.log("event=udbin_restore_after n=" + after);
            if (after > 0 && after * 2 > base) {
                lastKnownCount = Math.max(lastKnownCount, after);
                // 备份已被消费，改名留痕，避免下一轮重复还原
                try { bak.renameTo(new File(res, "ud.bin.backup.used")); } catch (Throwable ignore) {}
                return true;
            }
            // 没救回来：把坏文件放回去，避免留下一个「半好不坏」的库
            try {
                copyFile(broken, cur);
                XiaobuPhraseStore.restartVocabulary();
            } catch (Throwable ignore) {}
            return false;
        } catch (Throwable t) {
            MainHookLogger.log("event=udbin_restore_err " + t);
            return false;
        }
    }

    /** 原始 ud.bin 滚动备份（比 JSON 更贴近引擎真实状态，做最后兜底用），保留 {@link #UDBIN_KEEP} 份 */
    private static final int UDBIN_KEEP = 5;

    private static long lastUdbinSnapAt = 0L;

    private static void snapshotUdbinRaw(File dir) {
        try {
            // 心跳体检与合并可能在同一秒各触发一次，节流 60s 免得白拷一份 900KB
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastUdbinSnapAt < 60_000L) return;
            lastUdbinSnapAt = nowMs;
            File res = new File(dir.getParentFile(), "resources");
            File cur = new File(res, "ud.bin");
            if (!cur.exists() || cur.length() < 4096) return;
            long used = udbinUsed(cur);
            if (used < 200 * 1024) {
                MainHookLogger.log("event=udbin_snap_skip used=" + used);
                return;
            }
            File snapDir = new File(dir, "ud_snapshot");
            snapDir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            File dst = new File(snapDir, "ud_bin_" + ts + ".bin");
            copyFile(cur, dst);
            File[] fs = snapDir.listFiles((d, n) -> n.startsWith("ud_bin_")
                    && n.endsWith(".bin") && !n.startsWith("ud_bin_broken_"));
            if (fs != null && fs.length > UDBIN_KEEP) {
                java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = UDBIN_KEEP; i < fs.length; i++) fs[i].delete();
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=udbin_snap_fail " + t);
        }
    }

    private static boolean restoreUdBin(File dir) {
        try {
            File bak = new File(new File(dir, "ud_snapshot"), "xb_dict_healthy.json");
            if (!bak.exists() || bak.length() < 32) {
                MainHookLogger.log("event=xbd_restore_nobak");
                return false;
            }
            boolean ok = XiaobuPhraseStore.importUserDict(bak.getAbsolutePath());
            MainHookLogger.log("event=xbd_restore_ok bytes=" + bak.length() + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            MainHookLogger.log("event=xbd_restore_err " + t);
            return false;
        }
    }

    /** 滚动备份：保留最近 {@link #SNAP_KEEP} 份带时间戳的健康词库，避免唯一备份被写坏后无从恢复 */
    private static final int SNAP_KEEP = 5;

    private static long lastRotateAt = 0L;

    private static void rotateSnapshot(File dst, File src) {
        try {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastRotateAt < 20_000L) return;   // 20s 内不重复留档
            lastRotateAt = nowMs;
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            copyFile(src, new File(dst, "xb_dict_healthy_" + ts + ".json"));
            File[] fs = dst.listFiles((d, n) -> n.startsWith("xb_dict_healthy_") && n.endsWith(".json"));
            if (fs == null || fs.length <= SNAP_KEEP) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = SNAP_KEEP; i < fs.length; i++) fs[i].delete();
        } catch (Throwable t) {
            MainHookLogger.log("event=xbd_rotate_fail " + t);
        }
    }

    private static void writeFlag(File dir, String name, String content) {
        try {
            FileOutputStream fo = new FileOutputStream(new File(dir, name), false);
            fo.write((content + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignore) {}
    }

    private static void copyFile(File from, File to) throws Exception {
        FileInputStream fi = new FileInputStream(from);
        FileOutputStream fo = new FileOutputStream(to, false);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fi.read(buf)) > 0) fo.write(buf, 0, n);
            fo.flush();
            fo.getFD().sync();
        } finally {
            try { fi.close(); } catch (Throwable ignore) {}
            try { fo.close(); } catch (Throwable ignore) {}
        }
    }

    private static int countWordKeys(String json) {
        int n = 0, i = 0;
        while ((i = json.indexOf("\"word\":\"", i)) >= 0) { n++; i += 8; }
        return n;
    }

    // ─────────────────────── 词库审计（定位「词凭空消失」） ───────────────────────

    /** 上一轮词表快照 / 丢失现场文件都放在这个文件名前缀下 */
    private static final String PREV_WORDS = "xd_words_prev.txt";
    private static final String LOSS_PREFIX = "xd_loss_";

    /**
     * 与上一轮导出结果做差分。
     *
     * 为什么要做：宿主导入是合并语义、模块也从不删词，但现场出现过「词一个个少掉」，
     * 单看总词量只能看到 12860→12859 这种微降，无法定位是哪些词、也无法判断是
     * 引擎重建还是别的写入方。这里每轮把完整词表（含拼音的 full form）落盘，
     * 下一轮差出 removed 集合并点名写日志 + 落一份现场文件。
     */
    static void auditDiff(File dir, Set<String> nowFull) {
        try {
            // 护栏：空/极小的词库不许刷新基线，也不许判定「丢了」。
            // 2026-10-28 实测：塌库那一轮 now=0，直接把 14,828 词的基线写成 0 字节，
            // 之后即使把库救回来，审计也会把「全部词」当成新增，永远查不出真丢的那几个。
            if (nowFull == null || nowFull.size() < 200) {
                MainHookLogger.log("event=xbd_audit_skip now=" + (nowFull == null ? -1 : nowFull.size()));
                return;
            }
            File prev = new File(dir, PREV_WORDS);
            List<String> old = readLines(prev);
            if (old.size() < 200) {
                // 没有可用基线（首次运行 / 上次被塌库清空）→ 直接以当前为基线，不产生丢词报告
                writeLines(prev, nowFull);
                MainHookLogger.log("event=xbd_audit_baseline n=" + nowFull.size());
                return;
            }
            if (!old.isEmpty()) {
                Set<String> oldSet = new LinkedHashSet<>(old);
                List<String> removed = new java.util.ArrayList<>();
                for (String w : oldSet) if (!nowFull.contains(w)) removed.add(w);
                List<String> added = new java.util.ArrayList<>();
                for (String w : nowFull) if (!oldSet.contains(w)) added.add(w);

                if (!removed.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < removed.size() && i < 12; i++) {
                        sb.append('|').append(baseWord(removed.get(i)));
                    }
                    MainHookLogger.log("event=xbd_loss n=" + removed.size()
                            + " now=" + nowFull.size() + " prev=" + oldSet.size() + " sample=" + sb);
                    writePostMortem(dir, removed, nowFull.size());
                }
                if (!added.isEmpty()) {
                    MainHookLogger.log("event=xbd_added n=" + added.size());
                }
            }
            writeLines(prev, nowFull);
        } catch (Throwable t) {
            MainHookLogger.log("event=xbd_audit_fail " + t);
        }
    }

    /**
     * 定时体检：**不依赖「微信来新词」**。
     * 引擎塌库、个别词消失都可以在没有新消息的空窗期发生；旧实现只在 mergeInto 里检测，
     * 一旦当天没有新词，看门狗就永远不会跑（2026-10-28 03:45 那次之后 4 小时无人发现）。
     */
    static void periodicCheck(File dir) {
        synchronized (SYNC_LOCK) {
            try {
                File f = new File(dir, "xb_dict_watch.json");
                f.delete();
                if (!XiaobuPhraseStore.exportUserDict(f.getAbsolutePath())) return;
                String json = readText(f);
                if (json == null || json.length() < 32) return;
                Set<String> full = fullWords(json);
                Set<String> bases = new LinkedHashSet<>();
                for (String w : full) bases.add(baseWord(w));
                File udbin = new File(dir.getParentFile(), "resources/ud.bin");
                long used = udbinUsed(udbin);
                MainHookLogger.log("event=xbd_watch now=" + bases.size() + " udbin_used=" + used);
                auditDiff(dir, full);
                // 顺序很重要：**先**判断这一轮是不是塌了，只有健康才刷新备份。
                // 反过来会把「塌库后的空导出」写进备份，毁掉唯一的还原素材。
                boolean collapsed = checkCollapse(dir, bases.size(), used);
                if (!collapsed) {
                    // 空窗期也要刷新滚动备份：塌库往往就发生在「没有新词」的静默期，
                    // 只靠 mergeInto 备份会让 ud_snapshot 越来越旧。
                    snapshotFrom(dir, bases.size(), f);
                }
            } catch (Throwable t) {
                MainHookLogger.log("event=xbd_watch_fail " + t);
            }
        }
    }

    /** 从导出 JSON 里提取「完整词形（含拼音）」集合 */
    static Set<String> fullWords(String json) {
        Set<String> s = new LinkedHashSet<>();
        Matcher m = WORD_RE.matcher(json);
        while (m.find()) s.add(unescape(m.group(1)));
        return s;
    }

    /** 把丢失的词完整落一份现场文件，事后可直接拿来分析或回灌 */
    private static void writePostMortem(File dir, List<String> removed, int nowCount) {
        try {
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            File f = new File(dir, LOSS_PREFIX + ts + ".txt");
            FileOutputStream fo = new FileOutputStream(f, false);
            fo.write(("# now=" + nowCount + " removed=" + removed.size()
                    + " time=" + ts + "\n").getBytes("UTF-8"));
            for (String w : removed) {
                fo.write((w + "\n").getBytes("UTF-8"));
            }
            fo.flush();
            fo.getFD().sync();
            fo.close();
            MainHookLogger.log("event=xbd_loss_file " + f.getName());
        } catch (Throwable t) {
            MainHookLogger.log("event=xbd_loss_file_fail " + t);
        }
    }

    private static List<String> readLines(File f) {
        List<String> out = new java.util.ArrayList<>();
        if (!f.exists()) return out;
        try {
            String s = readText(f);
            if (s == null) return out;
            for (String line : s.split("\n")) {
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Throwable ignore) {}
        return out;
    }

    private static void writeLines(File f, Set<String> words) {
        FileOutputStream fo = null;
        try {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            fo = new FileOutputStream(tmp, false);
            for (String w : words) {
                fo.write((w + "\n").getBytes("UTF-8"));
            }
            fo.flush();
            fo.getFD().sync();
            fo.close();
            fo = null;
            if (f.exists() && !f.delete()) return;
            tmp.renameTo(f);
        } catch (Throwable t) {
            MainHookLogger.log("event=xbd_audit_write_fail " + t);
        } finally {
            if (fo != null) try { fo.close(); } catch (Throwable ignore) {}
        }
    }

    /** 诊断用：手动触发一次恢复 */
    static boolean restoreNow(File dir) {
        boolean ok = restoreUdBin(dir);
        if (ok) XiaobuPhraseStore.restartVocabulary();
        return ok;
    }

    /** 诊断用：手动留一份健康备份 */
    static void snapshotUdBinPub(File dir, int count) {
        snapshotUdBin(dir, count);
    }

    /** 在 json 的 "<key>":[ ... ] 数组末尾插入一段元素；找不到返回 null */
    private static String insertIntoArray(String json, String key, String add) {
        int cs = json.indexOf("\"" + key + "\"");
        if (cs < 0) return null;
        int arr = json.indexOf('[', cs);
        if (arr < 0) return null;
        int depth = 0;
        int end = -1;
        for (int i = arr; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {                      // 跳过字符串
                i++;
                while (i < json.length()) {
                    char d = json.charAt(i);
                    if (d == '\\') { i += 2; continue; }
                    if (d == '"') break;
                    i++;
                }
                continue;
            }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        if (end < 0) return null;
        boolean empty = json.substring(arr + 1, end).trim().isEmpty();
        return json.substring(0, end) + (empty ? "" : ",") + add + json.substring(end);
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Exception ex) { sb.append(n); }
                    }
                    break;
                default: sb.append(n);
            }
        }
        return sb.toString();
    }

    private static String readText(File f) {
        try {
            FileInputStream fi = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fi.read(buf)) > 0) bos.write(buf, 0, n);
            fi.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
