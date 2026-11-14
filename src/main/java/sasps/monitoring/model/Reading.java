package sasps.monitoring.model;

public record Reading(Double value, String unavailableReason) {
    public Reading {
        if (value != null && (!Double.isFinite(value) || unavailableReason != null)) {
            throw new IllegalArgumentException("A reading must be finite and have no unavailable reason");
        }
        if (value == null && (unavailableReason == null || unavailableReason.isBlank())) {
            throw new IllegalArgumentException("An unavailable reading needs a reason");
        }
    }

    public static Reading available(double value) {
        return new Reading(value, null);
    }

    public static Reading unavailable(String reason) {
        return new Reading(null, reason);
    }

    public static Reading nonNegative(double value, String reason) {
        return value >= 0 && Double.isFinite(value) ? available(value) : unavailable(reason);
    }
}
