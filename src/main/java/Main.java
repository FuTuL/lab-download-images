import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

public class Main {
    public static void main(String[] args) {
        try {
            var p = new Properties();
            var propsPath = "default.properties";
            if (new File("local.properties").exists()) {
                propsPath = "local.properties";
            }
            p.load(new FileInputStream(propsPath));

            var trueMain = new Controller(p);
            trueMain.Run();
        } catch (IOException e) {
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).warning(e.getMessage());
        }
    }
}
