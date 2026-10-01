package com.rey.modelquery.sample.plainjpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** AC-RDM-01: define a model, generate its QModel and page a query in under 20 lines, with no Spring. */
class QuickStartTest {

    @Test
    void ac_rdm_01_theFlowPagesInUnderTwentyLinesWithoutSpring() throws Exception {
        var source = Files.readAllLines(Path.of("src/main/java/com/rey/modelquery/sample/plainjpa/QuickStart.java"));
        assertThat(source).hasSizeLessThan(20);
        assertThat(String.join("\n", source)).doesNotContain("springframework");

        try (var sessionFactory = ShopTour.sessionFactory("quickstart")) {
            var page = sessionFactory.fromSession(em -> {
                ShopTour.seed(em);
                return QuickStart.firstPaidPage(em);
            });
            assertThat(page.content()).extracting(OrderView::getId).containsExactly(1L, 2L);
            assertThat(page.total()).hasValue(3);
        }
    }
}
