package erverse;

/**
 * Raised when registration or reassessment is attempted with invalid details:
 * a blank patient name, an impossible age, an out-of-range vital sign or an
 * unknown severity label.
 */
public class InvalidPatientDataException extends TriageException {

    private static final long serialVersionUID = 1L;

    public InvalidPatientDataException(String message) {
        super(message);
    }
}
