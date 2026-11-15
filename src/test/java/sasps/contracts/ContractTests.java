package sasps.contracts;

import static sasps.TestSuite.check;
import static sasps.TestSuite.expect;
import static sasps.TestSuite.sample;

public final class ContractTests {
    private ContractTests() {
    }

    public static void run() {
        check(Reading.available(42).value() == 42, "Available value");
        check(Reading.nonNegative(-1, "unsupported").value() == null, "Unsupported is null, not zero");
        check(Reading.nonNegative(-1, "unsupported").unavailableReason().equals("unsupported"),
            "Unsupported reading keeps its reason");
        expect(IllegalArgumentException.class, () -> Reading.available(Double.NaN));
        expect(IllegalArgumentException.class, () -> Reading.available(Double.POSITIVE_INFINITY));
        expect(IllegalArgumentException.class, () -> Reading.unavailable(""));
        expect(IllegalArgumentException.class, () -> new Reading(1.0, "contradictory reason"));
        expect(UnsupportedOperationException.class, () -> sample(1).readings().clear());
    }
}
