package de.energy6.caravanleveler;

import static java.lang.Math.abs;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

public class IsCloseTo extends TypeSafeMatcher<Float> {
    private final float delta;
    private final float value;

    public IsCloseTo(Float value, Float error) {
        this.delta = error;
        this.value = value;
    }

    @Override
    public boolean matchesSafely(Float item) {
        return actualDelta(item) <= 0.0;
    }

    @Override
    public void describeMismatchSafely(Float item, Description mismatchDescription) {
        mismatchDescription.appendValue(item)
                .appendText(" differed by ")
                .appendValue(actualDelta(item))
                .appendText(" more than delta ")
                .appendValue(delta);
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("a numeric value within ")
                .appendValue(delta)
                .appendText(" of ")
                .appendValue(value);
    }

    private Float actualDelta(Float item) {
        return abs(item - value) - delta;
    }

    /**
     * Creates a matcher of {@link Float}s that matches when an examined Float is equal
     * to the specified <code>operand</code>, within a range of +/- <code>error</code>.
     * For example:
     * <pre>assertThat(1.03, is(closeTo(1.0, 0.03)))</pre>
     *
     * @param operand
     *     the expected value of matching Floats
     * @param error
     *     the delta (+/-) within which matches will be allowed
     */
    public static Matcher<Float> closeTo(Float operand, Float error) {
        return new IsCloseTo(operand, error);
    }
}

