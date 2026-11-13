package fish.payara.server.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public class FakeAsadmin {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("calls.txt"), String.join("\t", args) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (Arrays.asList(args).contains("start-domain")) {
            System.out.print("Payara Server 5 startup");
            System.out.flush();
            Thread.sleep(50);
            System.out.println(" time : ready");
            Thread.sleep(60_000);
        } else if (Arrays.asList(args).contains("fail")) {
            System.err.println("Deployment rejected");
            System.exit(7);
        } else {
            System.out.println("Command deploy executed successfully.");
        }
    }
}
