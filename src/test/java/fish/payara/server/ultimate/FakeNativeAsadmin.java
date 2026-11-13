package fish.payara.server.ultimate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;

public class FakeNativeAsadmin {
    public static void main(String[] args) throws Exception {
        var arguments = Arrays.asList(args);
        Files.writeString(Path.of("calls.txt"), String.join("\t", args) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        int password = arguments.indexOf("--passwordfile");
        if (password >= 0) {
            var properties = new Properties();
            try (var stream = Files.newInputStream(Path.of(arguments.get(password + 1)))) { properties.load(stream); }
            if (!"sëcret=#\\\nline".equals(properties.getProperty("AS_ADMIN_PASSWORD"))) System.exit(8);
            if (Files.getFileStore(Path.of(arguments.get(password + 1))).supportsFileAttributeView("posix")
                    && Files.getPosixFilePermissions(Path.of(arguments.get(password + 1))).stream().anyMatch(p -> !p.name().startsWith("OWNER_"))) System.exit(9);
        }
        if (arguments.contains("--dry-run=true")) {
            System.out.println(Path.of(System.getProperty("java.home"), "bin", "java"));
            System.out.println("-cp\n/path with spaces/glassfish.jar\n-Xmx128m\n-Ddomain.option=true");
            System.out.println("com.sun.enterprise.glassfish.bootstrap.ASMain\n-read-stdin\ntrue\n-verbose\ntrue");
            return;
        }
        Path state = Path.of("deployed.txt");
        var deployed = new ArrayList<>(Files.exists(state) ? Files.readAllLines(state) : java.util.List.<String>of());
        if (arguments.contains("list-applications")) {
            for (String name : deployed) System.out.println(name + "  <ear, web>");
        } else if (arguments.contains("deploy")) {
            String name = arguments.get(arguments.indexOf("--name") + 1);
            if (name.equals("fail")) {
                System.err.println("Deployment rejected");
                System.exit(7);
            }
            if (!deployed.contains(name)) deployed.add(name);
            Files.write(state, deployed);
        } else if (arguments.contains("undeploy")) {
            deployed.remove(arguments.get(arguments.indexOf("undeploy") + 1));
            Files.write(state, deployed);
        }
    }
}
