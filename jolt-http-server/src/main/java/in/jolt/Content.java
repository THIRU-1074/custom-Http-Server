package in.jolt;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import org.json.JSONObject;
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

    void add(String key, Object value) {
        json.put(key, value);
    }

    @Override
    void serialize() {
        serialized = json.toString().getBytes();
    }

    @Override
    void deserialize() {
        try{
        String str = new String(serialized, "ISO-8859-1");
        json = new JSONObject(str);
        }
        catch(IOException e){
            e.printStackTrace();
        }
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
        try {
            // create the object of ByteArrayInputStream class
            // and initialized it with the byte array.
            ByteArrayInputStream inStreambj = new ByteArrayInputStream(serialized);
            
            // read image from byte array
            BufferedImage newImage = ImageIO.read(inStreambj);
            // Ensure the directory exists
            File dir = new File(path);
            if (!dir.exists()) {
                dir.mkdirs(); // create directory if not exists
            }
            // write output image
            ImageIO.write(newImage, format, dir);
            System.out.println("Image generated from the byte array.");
        } catch (IOException e) {
            System.err.println("Failed to save image: " + e.getMessage());
            return;
        }
    }
}
