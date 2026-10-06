package erverse;

/**
 * Base type for every failure of the ERverse triage workflow (Chapter 4.7).
 *
 * <p>Subclasses are declared as separate files -
 * {@link NoDoctorsAvailableException}, {@link NoBedsAvailableException} and
 * {@link InvalidPatientDataException} - so that each public type lives in its
 * own compilation unit and the build stays free of the javac warning
 * "auxiliary class should not be accessed from outside its own source file".
 * Table 5.1 in the report grouped them under one module; see CHANGELOG.md.</p>
 *
 * <p>They are <em>checked</em> exceptions, as required by the Week 6 review, so
 * that every caller is obliged by the compiler to decide how a resource
 * shortage or a bad registration is reported to staff instead of ignoring
 * it.</p>
 */
public class TriageException extends Exception {

    private static final long serialVersionUID = 1L;

    public TriageException(String message) {
        super(message);
    }

    public TriageException(String message, Throwable cause) {
        super(message, cause);
    }
}
