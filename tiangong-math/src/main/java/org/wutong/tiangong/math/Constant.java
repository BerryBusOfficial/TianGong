package org.wutong.tiangong.math;

import java.util.HashMap;
import java.util.Map;

public class Constant {

    public static final String ALPHA = "Α";
    public static final String alpha = "α";
    public static final String BETA = "Β";
    public static final String beta = "β";
    public static final String GAMMA = "Γ";
    public static final String gamma = "γ";
    public static final String DELTA = "Δ";
    public static final String delta = "δ";
    public static final String EPSILON = "Ε";
    public static final String epsilon = "ε";
    public static final String ZETA = "Ζ";
    public static final String zeta = "ζ";
    public static final String ETA = "Η";
    public static final String eta = "η";
    public static final String THETA = "Θ";
    public static final String theta = "θ";
    public static final String IOTA = "Ι";
    public static final String iota = "ι";
    public static final String KAPPA = "Κ";
    public static final String kappa = "κ";
    public static final String LAMBDA = "Λ";
    public static final String lambda = "λ";
    public static final String MU = "Μ";
    public static final String mu = "μ";
    public static final String NU = "Ν";
    public static final String nu = "ν";
    public static final String XI = "Ξ";
    public static final String xi = "ξ";
    public static final String OMICRON = "Ο";
    public static final String omicron = "ο";
    public static final String PI = "Π";
    public static final String pi = "π";
    public static final String RHO = "Ρ";
    public static final String rho = "ρ";
    public static final String SIGMA = "Σ";
    public static final String sigma = "σ";
    public static final String TAU = "Τ";
    public static final String tau = "τ";
    public static final String UPSILON = "Υ";
    public static final String upsilon = "υ";
    public static final String PHI = "Φ";
    public static final String phi = "φ";
    public static final String CHI = "Χ";
    public static final String chi = "χ";
    public static final String PSI = "Ψ";
    public static final String psi = "ψ";
    public static final String OMEGA = "Ω";
    public static final String omega = "ω";
    private static final Map<String, Number> CONSTANT_MAP = new HashMap<>();

    @SuppressWarnings("unchecked")
    public static <T extends Number> T definitionConstant(String constantVariable, T constantValue) {
        return (T) CONSTANT_MAP.put(constantVariable, constantValue);
    }

    public static Number getConstant(String constantVariable) {
        return CONSTANT_MAP.get(constantVariable);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Number> T updateConstant(String constantVariable, T constantValue) {
        return (T) CONSTANT_MAP.put(constantVariable, constantValue);
    }

}
