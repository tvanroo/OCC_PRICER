package com.cardpricer.cloud.catalog;

import java.math.BigDecimal;
import java.util.UUID;

public record CardRow(UUID id, String name, String setCode, String setName, String collectorNumber, String rarity,
                      String lang, BigDecimal usd, BigDecimal usdFoil, BigDecimal usdEtched, String imageSmall) {

    /** Market price for a finish, or null when Scryfall has none. */
    public BigDecimal marketFor(String finish) {
        return switch (finish) {
            case "normal" -> usd;
            case "foil" -> usdFoil;
            case "etched" -> usdEtched;
            default -> throw new IllegalArgumentException("Unknown finish: " + finish);
        };
    }
}
