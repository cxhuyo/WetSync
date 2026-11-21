package com.cxhuyo.wetsync;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 小布输入法「常用语」读写层 —— 走宿主自己的 Kernel API，不碰底层存储。
 *
 * 常用语**不是**存在 Room 的 tab_phrase 表里（那张表只有建表语句、全 APK 无读写 SQL，
 * 属于遗留空表），真实数据在 native 引擎的 LevelDB（files/resources/phrases_db），
 * 运行期被引擎独占，外部无法安全写入。
 *
 * 宿主暴露了完整接口（com.oplus.keyboard.kernel.Kernel，Kotlin object）：
 *   phraseGetCount()                              -> int   （上限 10000）
 *   phraseQuery(inputCode, content, label, limit) -> List<Phrase>
 *   phraseInsert(Phrase)                          -> int
 *   phraseDelete(Phrase)                          -> boolean
 *   phraseSyncExport(filePath) / phraseSyncImport(filePath) -> boolean
 * Phrase(input_code, content, label, primary_key, isFold, isSideslip, update_time)
 *
 * 模块跑在 com.oplus.keyboard 进程内，直接反射调用即可，引擎自带 ReentrantLock 保护。
 */
final class XiaobuPhraseStore {

    private static volatile boolean ready;
    private static Object kernel;
    private static Method mCount, mQuery, mInsert, mDelete;
    private static Constructor<?> ctorPhrase;
    private static Class<?> clsPhrase;

    static synchronized boolean init() {
        if (ready) return kernel != null;
        ready = true;
        try {
            ClassLoader cl = XiaobuInject.ctx != null
                    ? XiaobuInject.ctx.getClassLoader()
                    : XiaobuPhraseStore.class.getClassLoader();
            Class<?> k = Class.forName("com.oplus.keyboard.kernel.Kernel", true, cl);
            Field inst = k.getField("INSTANCE");
            kernel = inst.get(null);
            mCount = k.getMethod("phraseGetCount");
            mInsert = k.getMethod("phraseInsert", Class.forName("com.oplus.keyboard.kernel.Phrase", true, cl));
            mQuery = k.getMethod("phraseQuery", String.class, String.class, String.class, int.class);
            mDelete = k.getMethod("phraseDelete", Class.forName("com.oplus.keyboard.kernel.Phrase", true, cl));
            clsPhrase = Class.forName("com.oplus.keyboard.kernel.Phrase", true, cl);
            ctorPhrase = clsPhrase.getConstructor(String.class, String.class, String.class,
                    String.class, boolean.class, boolean.class, long.class);
            MainHookLogger.log("event=phrase_api_ok kernel=" + (kernel != null));
        } catch (Throwable t) {
            MainHookLogger.log("event=phrase_api_fail " + t);
            kernel = null;
        }
        return kernel != null;
    }

    private static Method mUdExport, mUdImport, mRestartVocab;

    /**
     * 词库变更后让引擎重新加载 —— Kernel.restartForVocabulary()。
     * 不加这一步，import 写进去的词条不会生效（引擎只在启动时装载词库），
     * 再 export 也还是旧内容。
     */
    static void restartVocabulary() {
        if (!init()) return;
        try {
            if (mRestartVocab == null) {
                mRestartVocab = kernel.getClass().getMethod("restartForVocabulary");
            }
            mRestartVocab.invoke(kernel);
            MainHookLogger.log("event=ud_restart_engine ok");
        } catch (Throwable t) {
            MainHookLogger.log("event=ud_restart_engine_fail " + t);
        }
    }

    /**
     * 小布「个人词库」导出 —— Kernel.userDictSyncExport 直接转 native
     * (Engine.INSTANCE.userDictSyncExport)。与常用语那套 phraseSyncExport 同构，
     * 好处是不用碰引擎的私有存储。
     */
    static boolean exportUserDict(String path) {
        if (!init()) return false;
        try {
            if (mUdExport == null) {
                mUdExport = kernel.getClass().getMethod("userDictSyncExport", String.class);
            }
            Object r = mUdExport.invoke(kernel, path);
            MainHookLogger.log("event=ud_export_call r=" + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            MainHookLogger.log("event=ud_export_fail " + t);
            return false;
        }
    }

    /** 小布「个人词库」导入 —— Kernel.userDictSyncImport → native */
    static boolean importUserDict(String path) {
        if (!init()) return false;
        try {
            if (mUdImport == null) {
                mUdImport = kernel.getClass().getMethod("userDictSyncImport", String.class);
            }
            Object r = mUdImport.invoke(kernel, path);
            MainHookLogger.log("event=ud_import_call r=" + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            MainHookLogger.log("event=ud_import_fail " + t);
            return false;
        }
    }

    private static Method mUpdateWord;

    private static Method mCharPinyin, mCompPy;

    /**
     * 用宿主自己的拼音表把一个词转成拼音（空格分隔）。
     *
     * 为什么必须用宿主的：{@code userDictSyncImport} 对每一条词条都会按「字 -> 读音」
     * 校验拼音（native ResCharPron），**对不上就静默丢弃**，但整个导入仍返回 true。
     * 微信那边的拼音来自微信自己的词库，多音字/分词与宿主不一致时就会整条丢掉。
     * 实测：同一批词用微信拼音导入 0/10 落地，换成宿主拼音 10/10 落地。
     *
     * 优先调私有 getCharPinyin（无副作用）；退而用公开 getCompositionPY（会写
     * compositionPY 字段，可能影响正在输入的编码串，所以只作兜底）。
     *
     * @return 空格分隔的拼音；拿不到返回 ""
     */
    static String wordPinyin(String word) {
        if (!init() || word == null || word.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 0x4E00 || c > 0x9FFF) return "";      // 非汉字（数字/英文/符号）交给微信拼音兜底
            String[] rs = charReadings(String.valueOf(c));
            if (rs.length == 0) return "";
            if (sb.length() > 0) sb.append(' ');
            sb.append(rs[0]);
        }
        return sb.toString();
    }

    /**
     * 逐字校准拼音：微信给的音节如果本来就是宿主的合法读音就原样保留，
     * 对不上的字换成宿主的默认读音。全部字都查不到时返回 ""（交给调用方兜底）。
     *
     * 这样既不会改动微信原本正确的读音，又能救回那些「因为读音对不上被静默丢弃」的词。
     */
    static String reconcilePinyin(String word, String wxPy) {
        return reconcilePinyin(word, wxPy, null);
    }

    /**
     * @param prefer 宿主自己词库里「字 -> 常用读音」的映射（可为 null），
     *               用来在多音字里挑一个宿主默认读音，而不是盲取第一个。
     */
    static String reconcilePinyin(String word, String wxPy, java.util.Map<String, String> prefer) {
        if (!init() || word == null || word.isEmpty()) return "";
        String[] wxs = (wxPy == null ? "" : wxPy.trim()).split("\\s+");
        boolean aligned = wxs.length == word.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 0x4E00 || c > 0x9FFF) return "";
            String[] rs = charReadings(String.valueOf(c));
            if (rs.length == 0) return "";
            String pick = null;
            if (aligned) {
                String cand = wxs[i].toLowerCase();
                for (String r : rs) if (r.equals(cand)) { pick = cand; break; }
                if (pick == null) {
                    // 微信把 ü 写成 v，宿主可能写成 u（或反过来），互相折算一次
                    String alt = cand.indexOf('v') >= 0 ? cand.replace('v', 'u') : cand.replace('u', 'v');
                    for (String r : rs) if (r.equals(alt)) { pick = alt; break; }
                }
            }
            if (pick == null && prefer != null) {
                String p = prefer.get(String.valueOf(c));
                if (p != null) for (String r : rs) if (r.equals(p)) { pick = p; break; }
            }
            if (pick == null) pick = rs[0];
            if (sb.length() > 0) sb.append(' ');
            sb.append(pick);
        }
        return sb.toString();
    }

    private static final java.util.HashMap<String, String[]> PY_CACHE = new java.util.HashMap<>();

    /** 单字全部读音（去声调、ü 写成 v、小写），带缓存；拿不到返回空数组 */
    private static String[] charReadings(String ch) {
        String[] hit = PY_CACHE.get(ch);
        if (hit != null) return hit;
        String[] out = new String[0];
        try {
            if (mCharPinyin == null) {
                mCharPinyin = kernel.getClass().getDeclaredMethod("getCharPinyin", String.class);
                mCharPinyin.setAccessible(true);
            }
            Object r = mCharPinyin.invoke(kernel, ch);
            String s = r == null ? "" : r.toString().trim();
            if (!s.isEmpty()) {
                String[] parts = s.split(",");
                java.util.ArrayList<String> list = new java.util.ArrayList<>();
                for (String p : parts) {
                    String v = stripTone(p);
                    if (!v.isEmpty() && !list.contains(v)) list.add(v);
                }
                out = list.toArray(new String[0]);
            }
        } catch (Throwable ignore) {
        }
        if (PY_CACHE.size() < 8000) PY_CACHE.put(ch, out);
        return out;
    }

    /** "ài" -> "ai"，"nǚ" -> "nv"；与宿主导出格式（无声调、v 代 ü）保持一致 */
    private static String stripTone(String s) {
        String t = s.replaceAll("[üǖǘǚǜÜǕǖǗǘǙǚǛǜ]", "v");
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD);
        t = t.replaceAll("\\p{M}+", "");
        return t.trim().toLowerCase();
    }

    private static String normPy(String s) {
        return s.replace(',', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * 小布「个人词库」单条更新 —— Kernel.updateUserWord(old, new) → Engine → native。
     * 宿主没有单独的新增/删除 API，这是唯一能改单条词的入口。
     * 成功返回 true；具体语义（改名 / 清空即删除 / 拼音+词）由调用方按实测结果使用。
     */
    static boolean updateUserWord(String oldWord, String newWord) {
        if (!init()) return false;
        try {
            if (mUpdateWord == null) {
                mUpdateWord = kernel.getClass().getMethod("updateUserWord", String.class, String.class);
            }
            Object r = mUpdateWord.invoke(kernel, oldWord, newWord);
            MainHookLogger.log("event=ud_update_word old=" + oldWord + " new=" + newWord + " r=" + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            MainHookLogger.log("event=ud_update_word_fail " + t);
            return false;
        }
    }

    /** 一条常用语（code = 输入码，content = 内容）。id 仅用于删除时定位。 */    static final class Phrase {
        String code = "";
        String content = "";
        String label = "";
        String primaryKey = "";
        Object raw;
        public String key() { return XiaobuPhraseStore.key(code, content); }
    }

    static String key(String code, String content) {
        return (code == null ? "" : code.trim()) + "\u0001" + (content == null ? "" : content.trim());
    }

    /** 读取本机全部常用语（limit 传 0 表示不限） */
    static List<Phrase> list() {
        List<Phrase> out = new ArrayList<>();
        if (!init()) return out;
        try {
            Object r = mQuery.invoke(kernel, "", "", "", 0);
            if (!(r instanceof List)) {
                MainHookLogger.log("event=phrase_query_odd " + (r == null ? "null" : r.getClass().getName()));
                return out;
            }
            for (Object o : (List<?>) r) {
                if (o == null) continue;
                Phrase p = new Phrase();
                p.raw = o;
                p.code = str(o, "getInput_code");
                p.content = str(o, "getContent");
                p.label = str(o, "getLabel");
                p.primaryKey = str(o, "getPrimary_key");
                if (p.content == null || p.content.trim().isEmpty()) continue;
                out.add(p);
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=phrase_list_fail " + t);
        }
        return out;
    }

    static int count() {
        if (!init()) return -1;
        try { return (Integer) mCount.invoke(kernel); } catch (Throwable t) { return -1; }
    }

    static Set<String> keys() {
        Set<String> s = new LinkedHashSet<>();
        for (Phrase p : list()) s.add(p.key());
        return s;
    }

    private static String str(Object o, String getter) {
        try {
            Method m = o.getClass().getMethod(getter);
            Object v = m.invoke(o);
            return v == null ? "" : v.toString();
        } catch (Throwable t) { return ""; }
    }

    /**
     * 批量导入（跳过已存在的 code+内容 组合）。
     * 注意：Kernel 的引擎锁在主线程被占时可能短暂阻塞，调用方应放在后台线程。
     * @return 实际新增条数
     */
    static int importMissing(List<Phrase> incoming) {
        if (incoming == null || incoming.isEmpty()) return 0;
        if (!init()) return 0;
        int added = 0;
        try {
            Set<String> have = keys();
            for (Phrase p : incoming) {
                if (p == null) continue;
                String k = p.key();
                if (k.equals("\u0001") || have.contains(k)) continue;
                Object ent = ctorPhrase.newInstance(
                        p.code == null ? "" : p.code,
                        p.content,
                        "",
                        "",
                        false, false, 0L);
                Object r = mInsert.invoke(kernel, ent);
                boolean ok = r instanceof Integer && ((Integer) r) >= 0;
                MainHookLogger.log("event=phrase_insert code=" + p.code + " text=" + p.content
                        + " ret=" + r + " ok=" + ok);
                if (ok) { have.add(k); added++; }
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=phrase_import_fail " + t);
        }
        MainHookLogger.log("event=phrase_import added=" + added);
        return added;
    }

    /** 删除一条（调试/回滚用） */
    static boolean remove(Phrase p) {
        if (p == null || !init()) return false;
        try {
            Object ent = p.raw != null ? p.raw : ctorPhrase.newInstance(
                    p.code == null ? "" : p.code, p.content, p.label == null ? "" : p.label,
                    p.primaryKey == null ? "" : p.primaryKey, false, false, 0L);
            Object r = mDelete.invoke(kernel, ent);
            MainHookLogger.log("event=phrase_delete text=" + p.content + " ret=" + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            MainHookLogger.log("event=phrase_delete_fail " + t);
            return false;
        }
    }
}
