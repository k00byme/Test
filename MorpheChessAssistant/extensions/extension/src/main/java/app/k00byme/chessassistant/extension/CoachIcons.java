package app.k00byme.chessassistant.extension;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Android port of the icon artwork and color/tint table from
 * "Chess.com Coach Icons (Play vs Computer)" v2.0.0 by Bjorn Nicolaij.
 *
 * The inline SVG fragments are shipped as VectorDrawable XML assets. Unknown
 * classification values are rejected: there is deliberately no APK drawable
 * lookup, emoji fallback, or generic icon fallback.
 */
final class CoachIcons {
    static final class Definition {
        final int color;
        final boolean tintSquares;
        final String assetName;

        Definition(int color, boolean tintSquares, String assetName) {
            this.color = color;
            this.tintSquares = tintSquares;
            this.assetName = assetName;
        }
    }

    private static final Map<String, Definition> DEFINITIONS;
    private static final Map<String, Drawable.ConstantState> ICON_CACHE = new HashMap<>();

    static {
        Map<String, Definition> values = new LinkedHashMap<>();
        values.put("brilliant", new Definition(0xFF1BACA6, true, "brilliant"));
        values.put("great", new Definition(0xFF749BBF, true, "great"));
        values.put("best", new Definition(0xFF81B64C, true, "best"));
        values.put("excellent", new Definition(0xFF81B64C, true, "excellent"));
        values.put("good", new Definition(0xFF95B776, true, "good"));
        values.put("book", new Definition(0xFFD5A47D, true, "book"));
        values.put("inaccuracy", new Definition(0xFFF7C631, true, "inaccuracy"));
        values.put("mistake", new Definition(0xFFFFA459, true, "mistake"));
        values.put("miss", new Definition(0xFFFF7769, true, "miss"));
        values.put("blunder", new Definition(0xFFFA412D, true, "blunder"));
        values.put("forced", new Definition(0xFF96AF8B, false, "forced"));
        DEFINITIONS = Collections.unmodifiableMap(values);
    }

    private CoachIcons() {}

    /** Returns a canonical Coach Icons key, or an empty string for unknown data. */
    static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.US);
        if (value.startsWith("move_classification_")) {
            value = value.substring("move_classification_".length());
        }
        if ("great_find".equals(value)) value = "great";
        return DEFINITIONS.containsKey(value) ? value : "";
    }

    static Definition definition(String classification) {
        String key = normalize(classification);
        return key.isEmpty() ? null : DEFINITIONS.get(key);
    }

    /**
     * Loads the original SVG-derived VectorDrawable asset for a known class.
     * Missing or malformed assets return null, never another icon.
     */
    static Drawable load(Context context, String classification) {
        if (context == null) return null;
        String key = normalize(classification);
        if (key.isEmpty()) return null;

        synchronized (ICON_CACHE) {
            Drawable.ConstantState cached = ICON_CACHE.get(key);
            if (cached != null) {
                return cached.newDrawable(context.getResources()).mutate();
            }
        }

        String asset = "coach-icons/" + key + ".xml";
        try (InputStream input = context.getAssets().open(asset)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            int event = parser.getEventType();
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = parser.next();
            }
            if (event != XmlPullParser.START_TAG || !"vector".equals(parser.getName())) {
                return null;
            }

            Drawable drawable = Drawable.createFromXml(context.getResources(), parser);
            if (drawable == null) return null;
            drawable = drawable.mutate();

            Drawable.ConstantState state = drawable.getConstantState();
            if (state != null) {
                synchronized (ICON_CACHE) {
                    ICON_CACHE.put(key, state);
                }
            }
            return drawable;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
