package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.internal.http.ConnectionHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ApiSurfaceTest {

    private static boolean mentionsConnectionHandler(Method m) {
        if (m.getReturnType() == ConnectionHandler.class) {
            return true;
        }
        for (Class<?> p : m.getParameterTypes()) {
            if (p == ConnectionHandler.class) {
                return true;
            }
        }
        return false;
    }

    @Test
    void publicApiNeverMentionsTheConnectionHandler() {
        for (Class<?> c :
                new Class<?>[] {
                    Response.class, Request.class, App.class, Router.class, JoltConfig.class
                }) {
            for (Method m : c.getMethods()) {
                assertTrue(!mentionsConnectionHandler(m), c.getSimpleName() + "." + m.getName());
            }
        }
    }

    @Test
    void connectionHandlerExposesNoSocketOrStreamAccessors() {
        Set<String> names = new TreeSet<>();
        for (Method m : ConnectionHandler.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                names.add(m.getName());
            }
        }
        for (String leaked : new String[] {"in", "out", "socket", "take", "guard", "config"}) {
            assertTrue(!names.contains(leaked), leaked);
        }
        assertEquals(Set.of("closeIfIdle", "reject", "respond", "run"), names);
    }
}
