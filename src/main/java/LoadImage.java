import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class LoadImage implements Runnable {
    private final URL url;
    private final int sizeLimit;
    private final String saveDir;
    private final Map<String, AtomicInteger> summary;

    public LoadImage(URL url, Map<String, AtomicInteger> summary, int sizeLimit, String saveDir) {
        this.url = url;
        this.summary = summary;
        this.sizeLimit = sizeLimit;
        this.saveDir = saveDir;
    }

    @Override
    public void run() {
        try {
            // 8 bytes is minimal image size for 1x1 white pixel in PBM format
            // we can ignore size check for size limit lesser then or equal 8
            if (sizeLimit < 9 || getContentLength() > sizeLimit) {
                loadImage();
                summary.get(Controller.SUCCESS).incrementAndGet();
            } else {
                summary.get(Controller.SKIPPED).incrementAndGet();
            }
        } catch (IOException e) {
            summary.get(Controller.FAILED).incrementAndGet();
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).warning(e.getMessage() + " on url: " + url);
        }
    }

    // send HEAD request to get content length
    private int getContentLength() {
        URLConnection conn = null;
        try {
            conn = url.openConnection();
            if (conn instanceof HttpURLConnection) {
                ((HttpURLConnection) conn).setRequestMethod("HEAD");
            }
            conn.getInputStream();
            return conn.getContentLength();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn instanceof HttpURLConnection) {
                ((HttpURLConnection) conn).disconnect();
            }
        }
    }

    // load image and save it as png (we don't need to check the mime type and save it in it's original format)
    private void loadImage() throws IOException {
        var image = ImageIO.read(url);
        if (image == null) {
            throw new IOException("Failed to load image");
        }
        var name = url.getPath().replaceAll("[^\\w.]+", "_");
        if (!name.matches("\\.png$")) {
            name += ".png";
        }
        var file = new File(saveDir + "/" + name);
        ImageIO.write(image, "png", file);
    }
}
