package com.germanreader.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;


final class ClientAddress {

    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientAddress() {
    }

    static String of(HttpServletRequest request, String trustedHeader) {
        String peer = request.getRemoteAddr();
        if (!trustedHeader.isBlank() && isPrivate(peer)) {
            String forwarded = request.getHeader(trustedHeader);
            if (forwarded != null && parse(forwarded.strip()) != null) {
                return normalize(forwarded.strip());
            }
        }
        return normalize(peer);
    }

    static String normalize(String address) {
        InetAddress parsed = parse(address);
        if (parsed == null) {
            return "unknown";
        }
        if (parsed instanceof Inet6Address) {
            byte[] bytes = parsed.getAddress();
            Arrays.fill(bytes, 8, 16, (byte) 0);
            try {
                return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
            } catch (UnknownHostException e) {
                return "unknown";
            }
        }
        return parsed.getHostAddress();
    }

    static boolean isPrivate(String address) {
        InetAddress parsed = parse(address);
        if (parsed == null) {
            return false;
        }
        if (parsed.isLoopbackAddress() || parsed.isSiteLocalAddress()) {
            return true;
        }
        return parsed instanceof Inet6Address && (parsed.getAddress()[0] & 0xFE) == 0xFC;
    }

    private static InetAddress parse(String address) {
        if (address == null || !IP_LITERAL.matcher(address).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
