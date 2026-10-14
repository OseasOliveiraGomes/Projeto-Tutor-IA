package br.com.sylph.service;

public final class VectorMath {
    private VectorMath() { }
    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1;
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { ab += a[i]*b[i]; aa += a[i]*a[i]; bb += b[i]*b[i]; }
        return aa == 0 || bb == 0 ? -1 : ab / Math.sqrt(aa * bb);
    }
}
