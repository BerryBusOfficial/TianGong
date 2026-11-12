package org.wutong.tiangong.user.util;

public class StringUtil {

    public static String subString(Object content, int begin, int end) {
        String ctt;
        if (content != null) {
            ctt = content.toString();
        } else {
            ctt = "null";
        }
        return ctt.substring(begin, Math.min(ctt.length(), end));
    }

}
