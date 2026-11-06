package dev.noella.imboni;

import dev.noella.imboni.eval.EvalRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full offline eval in CI and fails the build if a change makes things worse.
 * Thresholds are floors set just below current results, not targets.
 */
class EvalRegressionTest {

    @Test
    @SuppressWarnings("unchecked")
    void qualityFloorsHold(@TempDir Path dir) throws Exception {
        Map<String, Object> r = new EvalRunner().run(dir);

        Map<String, Object> retrieval = (Map<String, Object>) r.get("retrieval");
        assertThat(retrieval.get("permission_leaks")).isEqualTo(0);
        Map<String, Object> prod = (Map<String, Object>) retrieval.get("structure-180/HYBRID");
        assertThat((double) prod.get("recall_at_5")).isGreaterThanOrEqualTo(0.9);
        assertThat((double) prod.get("complete_evidence")).isGreaterThanOrEqualTo(0.85);

        Map<String, Object> all = (Map<String, Object>) ((Map<String, Object>) r.get("gate")).get("all");
        assertThat((int) all.get("false_answers")).isLessThanOrEqualTo(1);
        assertThat((int) all.get("false_refusals")).isLessThanOrEqualTo(2);

        Map<String, Object> e2e = (Map<String, Object>) r.get("end_to_end");
        assertThat(e2e.get("cross_tenant_answered")).isEqualTo(0);
        assertThat(e2e.get("invalid_structure")).isEqualTo(0);

        Map<String, Object> g = (Map<String, Object>) r.get("grounding_checker");
        Map<String, Object> fab = (Map<String, Object>) g.get("fabricated");
        Map<String, Object> num = (Map<String, Object>) g.get("number");
        assertThat((int) fab.get("caught")).isEqualTo((int) fab.get("total"));
        assertThat((int) num.get("caught")).isEqualTo((int) num.get("total"));
        assertThat(g.get("routed_to_review")).isEqualTo(g.get("lessons_with_errors"));

        Map<String, Object> ing = (Map<String, Object>) r.get("ingestion");
        assertThat(ing.get("transient_ok")).isEqualTo(true);
        assertThat(ing.get("partial_after_failure")).isEqualTo(0);
        assertThat(ing.get("recovered")).isEqualTo(true);
        assertThat(ing.get("duplicate_upload_deduped")).isEqualTo(true);
    }
}
