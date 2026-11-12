package com.taixu.tiangong.core.config;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class ApplicationArgs {

    private final String configPath;    // --tiangong.config=xxx.yaml
    private final String profile;       // --tiangong.profile=dev
    private final Map<String, String> raw = new HashMap<>();

    public ApplicationArgs(String... args) {
        String config = null, profile = null;
        if (args != null) {
            for (String arg : args) {
                if (arg == null || !arg.startsWith("--tiangong.")) continue;
                int idx = arg.indexOf('=');
                if (idx <= 0) continue;
                String key = arg.substring(2, idx);          // tiangong.config
                String value = arg.substring(idx + 1);
                raw.put(key, value);
                switch (key) {
                    case "tiangong.config" -> config = value;
                    case "tiangong.profile" -> profile = value;
                }
            }
        }
        this.configPath = config;
        this.profile = profile;
    }
}
