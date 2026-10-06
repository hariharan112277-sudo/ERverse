package erverse;

/**
 * Raised when a patient is ready for treatment but no doctor is free.
 *
 * <p>The patient is <em>not</em> removed from the waiting queue: the engine
 * peeks the heap root first and only dequeues once both a doctor and a bed have
 * been secured (see the corrected {@code treatNextPatient()} in
 * {@link TriageSystem}).</p>
 */
public class NoDoctorsAvailableException extends TriageException {

    private static final long serialVersionUID = 1L;

    public NoDoctorsAvailableException(String message) {
        super(message);
    }
}
