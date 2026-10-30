package com.skillmasterai.usecase;

import com.skillmasterai.modules.ingest.ValidatedUpload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a normalization is written into an audit row.
 *
 * <p>One shape for both submissions and writes, because the question the audit answers is the same
 * one on both: "why is this digest not the one my bytes would have made?" (v33 TD §数据模型).
 * Kept here rather than in the module that produced it: the audit detail is a use-case concern, and
 * the module has no opinion about rows.
 */
final class Normalizations {

    private Normalizations() {
    }

    static List<Map<String, Object>> forAudit(List<ValidatedUpload.Normalized> normalized) {
        List<Map<String, Object>> out = new ArrayList<>(normalized.size());
        for (ValidatedUpload.Normalized entry : normalized) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("path", entry.path());
            one.put("changes", entry.changes());
            out.add(one);
        }
        return out;
    }
}
