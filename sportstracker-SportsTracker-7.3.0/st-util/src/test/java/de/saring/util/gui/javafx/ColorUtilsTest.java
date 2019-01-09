package de.saring.util.gui.javafx;

import javafx.scene.paint.Color;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests of class ColorUtils.
 *
 * @author Stefan Saring
 */
public class ColorUtilsTest {

    /**
     * Tests of method toAwtColor().
     */
    @Test
    public void testToAwtColor() {
        javafx.scene.paint.Color fxColor = new javafx.scene.paint.Color(1.0d, 0.5d, 0d, 1d);
        assertEquals(new java.awt.Color(255, 128, 0), ColorUtils.toAwtColor(fxColor));
    }

    /**
     * Tests of method toFxColor().
     */
    @Test
    public void testToFxColor() {
        java.awt.Color awtColor = new java.awt.Color(255, 128, 0);
        // compare String representations, not double values
        assertEquals("0xff8000ff", ColorUtils.toFxColor(awtColor).toString());
    }

    /**
     * Tests of method toRGBCode().
     */
    @Test
    public void testToRGBCode() {
        assertEquals("#000000", ColorUtils.toRGBCode(Color.BLACK));
        assertEquals("#FFFFFF", ColorUtils.toRGBCode(Color.WHITE));
        assertEquals("#0A0B0C", ColorUtils.toRGBCode(Color.web("#0a0b0c")));
    }
}
