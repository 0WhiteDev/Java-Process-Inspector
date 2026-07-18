package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiHookProfileTest {
    @Test
    void matchesEverySupportedApiFamily() throws Exception {
        Set<ApiHookProfile> profiles = ApiHookProfile.parse("network, crypto, files, reflection, class_loading");

        assertEquals(5, profiles.size());
        assertTrue(ApiHookProfile.NETWORK.matches("java/net/Socket", "connect"));
        assertTrue(ApiHookProfile.NETWORK.matches("java/net/http/HttpClient", "send"));
        assertTrue(ApiHookProfile.NETWORK.matches("java/net/URL", "openConnection"));
        assertTrue(ApiHookProfile.CRYPTO.matches("javax/crypto/Cipher", "getInstance"));
        assertTrue(ApiHookProfile.CRYPTO.matches("javax/crypto/Cipher", "init"));
        assertTrue(ApiHookProfile.CRYPTO.matches("javax/crypto/Cipher", "doFinal"));
        assertTrue(ApiHookProfile.CRYPTO.matches("java/security/MessageDigest", "digest"));
        assertTrue(ApiHookProfile.FILES.matches("java/io/FileInputStream", "<init>"));
        assertTrue(ApiHookProfile.FILES.matches("java/io/FileOutputStream", "<init>"));
        assertTrue(ApiHookProfile.FILES.matches("java/nio/file/Files", "readAllBytes"));
        assertTrue(ApiHookProfile.FILES.matches("java/nio/file/Files", "write"));
        assertTrue(ApiHookProfile.REFLECTION.matches("java/lang/Class", "forName"));
        assertTrue(ApiHookProfile.REFLECTION.matches("java/lang/reflect/Method", "invoke"));
        assertTrue(ApiHookProfile.REFLECTION.matches("java/lang/reflect/Constructor", "newInstance"));
        assertTrue(ApiHookProfile.CLASS_LOADING.matches("java/lang/ClassLoader", "defineClass"));
        assertTrue(ApiHookProfile.CLASS_LOADING.matches("java/lang/invoke/MethodHandles$Lookup", "defineClass"));
        assertFalse(ApiHookProfile.CRYPTO.matches("java/net/Socket", "connect"));
    }

    @Test
    void rejectsUnknownAndEmptySelections() {
        assertThrows(IOException.class, () -> ApiHookProfile.parse("unknown"));
        assertThrows(IOException.class, () -> ApiHookProfile.parse(""));
    }
}
