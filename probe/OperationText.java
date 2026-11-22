package dev.nativeagent.probe;

import org.json.JSONException;
import org.json.JSONObject;

/** Only unwrap a whole response or code block. Never search prose for executable JSON. */
final class OperationText {
    static String unwrap(String value, String tag) {
        if (value == null || value.length() > 25000 || tag == null) return null;
        String text = value.strip();
        if (text.startsWith(tag) && text.length() > tag.length()
            && Character.isWhitespace(text.charAt(tag.length()))) text = text.substring(tag.length()).strip();
        if (text.startsWith("```")) {
            int line = text.indexOf('\n');
            if (line < 0 || !text.endsWith("\n```")) return null;
            String language = text.substring(3, line).strip();
            if (!language.isEmpty() && !language.equalsIgnoreCase("json")) return null;
            text = text.substring(line + 1, text.length() - 3).strip();
        }
        return text.startsWith("{") && text.endsWith("}") ? text : null;
    }

    static boolean matches(String value, String tag, String id) {
        if (tag == null || id == null || value.length() > 25000) return false;
        if (value.equals(tag) || value.startsWith(tag + " ") || value.startsWith(tag + "\n")) return true;
        String json = unwrap(value, tag);
        if (json == null || !json.contains(id)) return false;
        try {
            var object = new JSONObject(json);
            return id.equals(object.optString("id")) && object.has("tool")
                && tag.matches("NA[a-f0-9]{12}\\." + object.getInt("step") + "(r[1-2])?");
        } catch (JSONException invalid) { return false; }
    }
}
