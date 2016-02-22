package com.mediatek.internal.telephony.ltedc.svlte.apirat;

/**
 * AP IRAT information.
 * @hide
 */
public class ApIratInfo {
    /**
     * IRAT type.
     */
    public enum IratType {
        IRAT_TYPE_UNKNOWN(0),
        IRAT_TYPE_LTE_HRPD(1),
        IRAT_TYPE_HRPD_LTE(2);

        private static String[] sIratTypeStrings = {
            "Unknown type",
            "LTE -> HRPD",
            "HRPD -> LTE",
        };

        private int mCode;

        private IratType(int code) {
            mCode = code;
        }

        /**
         * Convert int value to IRAT type.
         * @param typeInt Int value of the type.
         * @return IRAT type of the int value.
         */
        public static IratType getIratTypeFromInt(int typeInt) {
            IratType type;

            switch (typeInt) {
                case 0:
                    type = IratType.IRAT_TYPE_UNKNOWN;
                    break;
                case 1:
                    type = IratType.IRAT_TYPE_LTE_HRPD;
                    break;
                case 2:
                    type = IratType.IRAT_TYPE_HRPD_LTE;
                    break;
                default:
                    throw new RuntimeException("Unrecognized IratType: "
                            + typeInt);
            }
            return type;
        }

        @Override
        public String toString() {
            return sIratTypeStrings[mCode];
        }
    };

    public int sourceRat = 0;
    public int targetRat = 0;
    public IratType type = IratType.IRAT_TYPE_UNKNOWN;

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("ApIratInfo: {").append("sourceRat=").append(sourceRat)
                .append(" targetRat=").append(targetRat).append(" type=")
                .append(type);
        sb.append("]}");
        return sb.toString();
    }

}
