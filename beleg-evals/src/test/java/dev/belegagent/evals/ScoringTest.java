package dev.belegagent.evals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import dev.belegagent.evals.Scoring.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringTest {

    private static final Beleg TRUTH = beleg("471102", "Lieferant GmbH", "198.00", "37.62", "235.62",
            List.of(line("Trennblätter A4", "198.00"), line("Joghurt Banane", "275.00")));

    @Test
    void perfectPredictionScoresOne() {
        Scoring.DocumentScore s = Scoring.score(TRUTH, TRUTH);

        assertThat(s.keyFieldsCorrect()).isTrue();
        assertThat(s.fields().values()).allMatch(v -> v == 1.0);
        assertThat(s.lineF1()).isEqualTo(1.0);
    }

    @Test
    void amountsAllowOneCentButNoMore() {
        Beleg oneCent = beleg("471102", "Lieferant GmbH", "198.01", "37.62", "235.62", TRUTH.positions());
        Beleg twoCents = beleg("471102", "Lieferant GmbH", "198.02", "37.62", "235.62", TRUTH.positions());

        assertThat(Scoring.score(TRUTH, oneCent).fields().get(Field.NET_TOTAL)).isEqualTo(1.0);
        assertThat(Scoring.score(TRUTH, twoCents).fields().get(Field.NET_TOTAL)).isEqualTo(0.0);
        assertThat(Scoring.score(TRUTH, twoCents).keyFieldsCorrect()).isFalse();
    }

    @Test
    void invoiceNumberIgnoresWhitespaceAndCase() {
        Beleg spaced = beleg(" 4711 02 ", "Lieferant GmbH", "198.00", "37.62", "235.62", TRUTH.positions());
        assertThat(Scoring.score(TRUTH, spaced).fields().get(Field.INVOICE_NUMBER)).isEqualTo(1.0);
    }

    @Test
    void anlsGivesPartialCreditAndZeroBelowHalf() {
        assertThat(Scoring.anls("Lieferant GmbH", "Lieferant GmbH")).isEqualTo(1.0);
        assertThat(Scoring.anls("Lieferant GmbH", "Lieferant Gmbh.")).isCloseTo(0.93, within(0.01));
        assertThat(Scoring.anls("Lieferant GmbH", "Bäckerei Schmidt")).isEqualTo(0.0);
        assertThat(Scoring.anls("Lieferant GmbH", null)).isEqualTo(0.0);
    }

    @Test
    void lineF1CountsMissingAndExtraRows() {
        // one of two rows found, plus one wrong row: precision 1/2, recall 1/2
        double f1 = Scoring.lineF1(TRUTH.positions(),
                List.of(line("Trennblätter A4", "198.00"), line("Joghurt Banane", "999.00")));
        assertThat(f1).isEqualTo(0.5);
        assertThat(Scoring.lineF1(TRUTH.positions(), List.of())).isEqualTo(0.0);
    }

    @Test
    void lineDescriptionMayContainExtraPrintedText() {
        assertThat(Scoring.descriptionSimilarity("Kunstrasen grün 3m breit",
                "Kunstrasen grün 3m breit\nWir erlauben uns Ihnen folgende Positionen aus der Lieferung zu berechnen"))
                .isEqualTo(1.0);
        assertThat(Scoring.descriptionSimilarity("Mineralwasser Medium 12 x 1,0l PET",
                "Mineralwasser Medium\n12 x 1,0l PET")).isEqualTo(1.0);
        assertThat(Scoring.descriptionSimilarity("Kunstrasen", "Schweinesteak")).isEqualTo(0.0);
    }

    @Test
    void missingPredictionScoresZero() {
        Scoring.DocumentScore s = Scoring.score(TRUTH, null);
        assertThat(s.keyFieldsCorrect()).isFalse();
        assertThat(s.fields().values()).allMatch(v -> v == 0.0);
    }

    @Test
    void fieldsWithoutTruthAreNotScored() {
        Scoring.DocumentScore s = Scoring.score(TRUTH, TRUTH);
        assertThat(s.fields()).doesNotContainKey(Field.SELLER_VAT_ID);
    }

    private static Beleg beleg(String number, String seller, String net, String tax, String gross, List<Position> lines) {
        return new Beleg(DocumentKind.INVOICE, seller, null, null, null, null, number, LocalDate.of(2018, 3, 5), null,
                "EUR", lines, List.of(), new BigDecimal(net), new BigDecimal(tax), new BigDecimal(gross), null, null);
    }

    @Test
    void vatBreakdownNeedsEveryRateWithNetAndTax() {
        List<TaxLine> truth = List.of(tax("19", "198.00", "37.62"), tax("7", "275.00", "19.25"));

        assertThat(Scoring.vatBreakdownMatches(truth, List.of(tax("7.00", "275.00", "19.25"), tax("19", "198.01", "37.62"))))
                .as("order and scale do not matter, 1 cent tolerance").isTrue();
        assertThat(Scoring.vatBreakdownMatches(truth, List.of(tax("19", "198.00", "37.62"))))
                .as("missing rate").isFalse();
        assertThat(Scoring.vatBreakdownMatches(truth, List.of(tax("19", "235.62", "37.62"), tax("7", "275.00", "19.25"))))
                .as("gross read as net").isFalse();
    }

    private static TaxLine tax(String rate, String net, String tax) {
        return new TaxLine(new BigDecimal(rate), new BigDecimal(net), new BigDecimal(tax), null);
    }

    private static Position line(String description, String total) {
        return new Position(description, null, null, new BigDecimal(total), null);
    }
}
