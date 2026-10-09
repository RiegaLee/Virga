package cn.huohuas001.virga.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiBoundaryTest {
    private static final List<String> FORBIDDEN_CLASS_REFERENCES = Arrays.asList(
        "net/minecraft/",
        "io/github/kloping/",
        "cn/huohuas001/virga/core/",
        "cn/huohuas001/virga/forge/",
        "com/alibaba/fastjson/"
    );

    @Test
    void compiledPublicApiDoesNotReferenceHostOrSdkPackages() throws IOException, URISyntaxException {
        Path classesRoot = Paths.get(ApiVersion.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        Files.walk(classesRoot)
            .filter(path -> path.toString().endsWith(".class"))
            .forEach(path -> assertNoForbiddenReference(path));
    }

    @Test
    void commandHandlersAreAsynchronousAndImagesUseBytes() throws Exception {
        Method handler = CommandHandler.class.getMethod("handle", CommandContext.class);
        assertTrue(CompletionStage.class.isAssignableFrom(handler.getReturnType()));

        Method replyImage = MessageGateway.class.getMethod(
            "replyImage",
            MessageReference.class,
            byte[].class,
            String.class,
            String.class,
            String.class
        );
        assertTrue(CompletionStage.class.isAssignableFrom(replyImage.getReturnType()));
    }

    private static void assertNoForbiddenReference(Path classFile) {
        try {
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            for (String forbidden : FORBIDDEN_CLASS_REFERENCES) {
                assertFalse(bytes.contains(forbidden), classFile + " references forbidden package " + forbidden);
            }
        } catch (IOException error) {
            throw new AssertionError("Failed to inspect " + classFile, error);
        }
    }
}
