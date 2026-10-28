package tv.naughty.dual;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;

final class Lan {
    private Lan() {
    }

    static String lanIp() {
        try {
            ArrayList<String> found = new ArrayList<>();
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                for (Inet4Address addr : onlyV4(iface)) {
                    String host = addr.getHostAddress();
                    if (host != null && !host.startsWith("127.")) {
                        found.add(host);
                    }
                }
            }
            for (String host : found) {
                if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                    return host;
                }
            }
            return found.isEmpty() ? null : found.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static ArrayList<Inet4Address> onlyV4(NetworkInterface iface) {
        ArrayList<Inet4Address> out = new ArrayList<>();
        Collections.list(iface.getInetAddresses()).forEach(addr -> {
            if (addr instanceof Inet4Address) {
                out.add((Inet4Address) addr);
            }
        });
        return out;
    }
}
