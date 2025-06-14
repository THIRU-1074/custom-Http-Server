package in.jolt;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import java.nio.charset.StandardCharsets;

abstract class Content {

    byte[] serialized;
    int contentLength;
    String contentType;

    abstract void deserialize();

    abstract void serialize();
}

class JSON extends Content {

    JSONObject json = new JSONObject();

    void add(String key, String value) {
        json.put(key, value);
    }

    @Override
    void serialize() {
        serialized = json.toJSONString().getBytes();
    }

    @Override
    void deserialize() {
        String str = new String(serialized, StandardCharsets.UTF_8);
        json = (JSONObject) JSONValue.parse(str);
    }

    String get(String key) {
        return (String) json.get(key);
    }
}

class text extends Content {

    @Override
    void serialize() {

    }

    @Override
    void deserialize() {

    }
}

class html extends Content {

    String path;

    html(String path) {
        this.path = path;
    }

    @Override
    void serialize() {
        try {
            serialized = Files.readAllBytes(Paths.get(path));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    void deserialize() {

    }
}

class image extends Content {

    String path;
    String format;

    image(String path) {
        this.path = path;
        format = path.substring(path.lastIndexOf('.') + 1);
    }
    @Override
    void serialize() {
        BufferedImage bImage;
        try {
            bImage = ImageIO.read(new File(path));
            // Serialize to file
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(bImage, format, bos);
            serialized = bos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    void deserialize() {

    }
}
