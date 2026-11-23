package dev.belegagent.evals;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One model setting to evaluate. */
record EvalConfig(String name, String model, String mediaResolution, String thinkingLevel) {

    /** Presets for the README comparisons: model choice and image resolution (accuracy vs cost). */
    static final Map<String, EvalConfig> PRESETS = presets();

    private static Map<String, EvalConfig> presets() {
        Map<String, EvalConfig> m = new LinkedHashMap<>();
        m.put("lite35-medium", new EvalConfig("lite35-medium", "gemini-3.5-flash-lite", "MEDIUM", null));
        m.put("lite35-low", new EvalConfig("lite35-low", "gemini-3.5-flash-lite", "LOW", null));
        m.put("lite31-medium", new EvalConfig("lite31-medium", "gemini-3.1-flash-lite", "MEDIUM", null));
        m.put("flash38-medium", new EvalConfig("flash38-medium", "gemini-3.8-flash", "MEDIUM", null));
        return Collections.unmodifiableMap(m);
    }
}
