package android.app;

/**
 * Stub class to prevent NoClassDefFoundError during MockK reflection on API < 31.
 * This class is bundled only in the test APK and satisfies the class loader when
 * it scans Activity methods like onPictureInPictureUiStateChanged(PictureInPictureUiState).
 */
public final class PictureInPictureUiState {
}
