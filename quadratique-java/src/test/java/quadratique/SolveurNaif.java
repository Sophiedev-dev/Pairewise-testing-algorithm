package quadratique;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solveur de DÉMONSTRATION volontairement fragile : il utilise la formule
 * classique x = (-b ± √Δ) / 2a, qui perd ses chiffres significatifs quand
 * b ≫ a et c (cancellation catastrophique en flottants IEEE 754).
 *
 * Sert à montrer que l'oracle détecte ce défaut (bulles rouges) :
 *   mvn test -Dsolveur.classe=quadratique.SolveurNaif
 */
public final class SolveurNaif {

    public SolveurNaif() {
    }

    public Map<String, Object> resoudre(double a, double b, double c) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Object> solutions = new ArrayList<>();
        r.put("solutions", solutions);
        if (a == 0) {
            r.put("delta", null);
            if (b == 0) {
                r.put("nature", c == 0 ? "INFINITE_DE_SOLUTIONS" : "AUCUNE_SOLUTION");
            } else {
                r.put("nature", "LINEAIRE");
                solutions.add(-c / b);
            }
            return r;
        }
        double delta = b * b - 4 * a * c;
        r.put("delta", delta);
        if (delta > 0) {
            r.put("nature", "DEUX_REELLES");
            solutions.add((-b - Math.sqrt(delta)) / (2 * a));
            solutions.add((-b + Math.sqrt(delta)) / (2 * a));
        } else if (delta == 0) {
            r.put("nature", "RACINE_DOUBLE");
            solutions.add(-b / (2 * a));
        } else {
            double re = -b / (2 * a);
            double im = Math.sqrt(-delta) / (2 * Math.abs(a));
            r.put("nature", "COMPLEXES");
            solutions.add(new double[] {re, im});
            solutions.add(new double[] {re, -im});
            r.put("texte", "x = " + re + " ± " + im + "i");
        }
        return r;
    }
}
