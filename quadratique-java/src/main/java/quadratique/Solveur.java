package quadratique;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solveur d'équations du second degré : a·x² + b·x + c = 0.
 *
 * C'est le « code testé ». La classe de test (SolveurTest) ne l'importe jamais :
 * elle la charge et l'instancie par réflexion (Class.forName + newInstance)
 * à partir de son nom.
 *
 * Contrat de retour : une Map (types du JDK uniquement, pour que le test n'ait
 * besoin d'aucune classe du solveur) :
 *
 *   "nature"    | "delta" | "solutions"
 *   ------------|---------|-----------------------------------------------
 *   DEUX_REELLES          | Δ > 0 | [x1, x2]                    (Double)
 *   RACINE_DOUBLE         | Δ = 0 | [x0]                        (Double)
 *   COMPLEXES             | Δ < 0 | [{re, im}, {re, -im}]       (double[2]) + "texte" avec « i »
 *   LINEAIRE              | null  | [x]                         (a = 0, b ≠ 0)
 *   AUCUNE_SOLUTION       | null  | []                          (a = 0, b = 0, c ≠ 0)
 *   INFINITE_DE_SOLUTIONS | null  | []                          (a = b = c = 0)
 */
public final class Solveur {

    public Solveur() {
    }

    public Map<String, Object> resoudre(double a, double b, double c) {
        // ---- Cas a = 0 : ce n'est plus une équation du second degré ----
        if (a == 0) {
            if (b == 0) {
                return c == 0
                    ? resultat("INFINITE_DE_SOLUTIONS", null, new ArrayList<>(), "0 = 0 : infinité de solutions")
                    : resultat("AUCUNE_SOLUTION", null, new ArrayList<>(), c + " = 0 : impossible");
            }
            double x = -c / b;
            return resultat("LINEAIRE", null, liste(x), "x = " + x);
        }

        // ---- Cas a ≠ 0 : discriminant ----
        double delta = b * b - 4 * a * c;

        if (delta > 0) {
            // Formule numériquement stable : on évite -b + √Δ quand b² ≫ 4ac,
            // soustraction de deux nombres presque égaux qui détruit les chiffres.
            double signeB = b >= 0 ? 1 : -1;
            double q = -0.5 * (b + signeB * Math.sqrt(delta));
            double x1 = q / a;
            double x2 = c / q;
            return resultat("DEUX_REELLES", delta, liste(x1, x2), "x1 = " + x1 + ", x2 = " + x2);
        }

        if (delta == 0) {
            double x0 = -b / (2 * a);
            return resultat("RACINE_DOUBLE", delta, liste(x0), "x0 = " + x0);
        }

        double re = -b / (2 * a);
        double im = Math.sqrt(-delta) / (2 * Math.abs(a));
        List<Object> solutions = new ArrayList<>();
        solutions.add(new double[] {re, im});
        solutions.add(new double[] {re, -im});
        return resultat("COMPLEXES", delta, solutions, "x = " + re + " ± " + im + "i");
    }

    private static List<Object> liste(double... valeurs) {
        List<Object> l = new ArrayList<>();
        for (double v : valeurs) l.add(v);
        return l;
    }

    private static Map<String, Object> resultat(String nature, Double delta, List<Object> solutions, String texte) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nature", nature);
        r.put("delta", delta);
        r.put("solutions", solutions);
        r.put("texte", texte);
        return r;
    }
}
