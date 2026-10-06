package erverse;

/**
 * Raised when a patient is ready for treatment but no bed of a suitable type is
 * free. The patient remains queued at the head of the heap, so no critical case
 * is ever lost because the ward was momentarily full.
 */
public class NoBedsAvailableException extends TriageException {

    private static final long serialVersionUID = 1L;

    public NoBedsAvailableException(String message) {
        super(message);
    }
}
