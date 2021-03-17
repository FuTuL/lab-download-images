import org.jsoup.helper.HttpConnection;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

public class LoadDocument implements Runnable {
    public URL url;
    private Document doc;
    private int recursionLevel;
    public int recursionLimit;

    public String saveDir;
    public int sizeLimit;

    private final Map<String, AtomicInteger> summary;

    private final ThreadPoolExecutor imageTP;
    private final ThreadPoolExecutor documentTP;
    private final ReadWriteLock tpLock;

    private final HashSet<URL> checked;
    private final Lock checkedLock;

    private interface LoadCallback {
        Runnable load(URL src, Map<String, AtomicInteger> summary, int sizeLimit, String saveDir);
    }

    public LoadDocument(
            ThreadPoolExecutor imageTP,
            ThreadPoolExecutor documentTP,
            ReadWriteLock tpLock,
            HashSet<URL> checked,
            Lock checkedLock,
            Map<String, AtomicInteger> summary) {
        this.imageTP = imageTP;
        this.documentTP = documentTP;
        this.tpLock = tpLock;
        this.checked = checked;
        this.checkedLock = checkedLock;
        this.summary = summary;
    }

    // copy only base values
    @SuppressWarnings("CopyConstructorMissesField")
    public LoadDocument(LoadDocument old) {
        this(old.imageTP, old.documentTP, old.tpLock, old.checked, old.checkedLock, old.summary);
        saveDir = old.saveDir;
        recursionLimit = old.recursionLimit;
        sizeLimit = old.sizeLimit;
        url = old.url;
        recursionLevel = old.recursionLevel;
    }

    @Override
    public void run() {
        try {
            doc = HttpConnection.connect(url).get();
        } catch (IOException e) {
            if (recursionLevel == 0) {
                System.err.println(e.getMessage());
            }
            return;
        }
        loadEach("img", "src", imageTP, LoadImage::new);
        if (recursionLevel > recursionLimit) {
            return;
        }

        // load other documents recursively
        loadEach(
                "a",
                "href",
                documentTP,
                (URL src, Map<String, AtomicInteger> summary, int sizeLimit, String saveDir) -> {
                    var load = new LoadDocument(this);
                    load.url = src;
                    load.recursionLevel = recursionLevel + 1;
                    return load;
                }
        );
    }

    // Run Task in ThreadPool for each tag by query
    private void loadEach(String query, String attr, ThreadPoolExecutor tp, LoadCallback task) {
        var elements = doc.select(query);
        for (var i : elements) {
            URL src;
            try {
                src = new URL(url, i.attr(attr));
            } catch (IOException ignored) {
                // if error on nested links - do nothing
                continue;
            }
            checkedLock.lock();
            if (checked.contains(src)) {
                // skip checked links
                checkedLock.unlock();
                continue;
            }
            checked.add(src);
            checkedLock.unlock();

            tpLock.writeLock().lock();
            tp.submit(task.load(src, summary, sizeLimit, saveDir));
            tpLock.writeLock().unlock();
        }
    }
}
