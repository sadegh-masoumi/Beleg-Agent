package dev.belegagent.extraction.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;
import java.math.BigDecimal;

@Description("One line item")
public record ExtractedPosition(
        @JsonProperty(required = true)
        @Description("Item text as printed")
        String description,

        @Description("Quantity, or null if not printed (a weight such as 0,456 kg is a quantity)")
        BigDecimal quantity,

        @Description("Price per unit, or null if not printed")
        BigDecimal unitPrice,

        @JsonProperty(required = true)
        @Description("Line amount exactly as printed; negative for discounts and deposit returns (Pfand)")
        BigDecimal lineTotal,

        @Description("VAT rate of this line in percent (19, 7 or 0), resolved from the tax code letter if needed; null if not determinable")
        BigDecimal taxRatePercent) {
}
