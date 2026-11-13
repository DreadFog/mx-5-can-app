package com.mx5.dashboard;

public final class ModelYearFilter {
    private ModelYearFilter() { }

    public static boolean matches(int year, Integer from, Integer to, int[] years) {
        if (from == null && to == null && years.length == 0) return true;
        if (from != null && year >= from) return true;
        if (to != null && year <= to) return true;
        for (int listed : years) {
            if (year == listed) return true;
        }
        return false;
    }
}