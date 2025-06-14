package in.jolt;
import java.util.*;
import java.util.function.BiConsumer;

public class Handlers {
    static Map<String, ArrayList<BiConsumer<Request,Response>>> getHandlers;
    static Map<String, ArrayList<BiConsumer<Request,Response>>> postHandlers;
    static void initializeGET(){
        getHandlers=new HashMap<>();
    }
    static void initializePOST(){
        postHandlers=new HashMap<>();
    }
}
