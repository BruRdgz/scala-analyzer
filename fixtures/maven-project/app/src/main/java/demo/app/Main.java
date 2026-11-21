package demo.app;

import demo.core.Greeter;
import org.apache.commons.lang3.StringUtils;

public class Main {
    public static void main(String[] args) {
        System.out.println(StringUtils.capitalize(new Greeter().greet("world")));
    }
}
