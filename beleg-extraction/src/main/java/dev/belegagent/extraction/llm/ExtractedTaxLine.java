package dev.belegagent.extraction.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;
import java.math.BigDecimal;

@Description("One row of the VAT summary")
public record ExtractedTaxLine(
        @JsonProperty(required = true)
        @Description("Tax rate in percent, e.g. 19 or 7")
        BigDecimal ratePercent,

        @Description("Net amount for this rate, or null if not printed")
        BigDecimal net,

        @Description("Tax amount for this rate, or null if not printed")
        BigDecimal tax,

        @Description("Gross amount for this rate, or null if not printed")
        BigDecimal gross) {
}
