package br.com.sylph.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VectorMathTest {
    @Test void cosineOfSameAndOrthogonalVectors() {
        assertEquals(1.0, VectorMath.cosine(new double[]{1,0}, new double[]{1,0}), 1e-10);
        assertEquals(0.0, VectorMath.cosine(new double[]{1,0}, new double[]{0,1}), 1e-10);
        assertEquals(-1.0, VectorMath.cosine(new double[]{1}, new double[]{1,2}));
    }
}
