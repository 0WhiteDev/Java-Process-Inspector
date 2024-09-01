package uk.whitedev;

import java.net.URL;
import java.net.URLClassLoader;

public class InjectClassLoader extends URLClassLoader {
    public InjectClassLoader(URL url) {
        super(new URL[]{url});
    }
}

