package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.link.Addresses.Host;
import org.junit.jupiter.api.Test;

class AddressesTest {

  @Test
  void hostsAreClassifiedBeforeAnythingIsMatched() {
    assertThat(Addresses.classify("localhost")).isEqualTo(Host.THIS_MACHINE);
    assertThat(Addresses.classify("127.0.0.1")).isEqualTo(Host.THIS_MACHINE);
    assertThat(Addresses.classify("[::1]")).isEqualTo(Host.THIS_MACHINE);
    assertThat(Addresses.classify("10.0.0.5")).isEqualTo(Host.PRIVATE);
    assertThat(Addresses.classify("172.16.0.1")).isEqualTo(Host.PRIVATE);
    assertThat(Addresses.classify("172.31.255.255")).isEqualTo(Host.PRIVATE);
    assertThat(Addresses.classify("192.168.1.10")).isEqualTo(Host.PRIVATE);
    assertThat(Addresses.classify("0.0.0.0")).isEqualTo(Host.PRIVATE);
    assertThat(Addresses.classify("host.docker.internal")).isEqualTo(Host.PRIVATE);
    assertThat(Addresses.classify("mail.corp.local")).isEqualTo(Host.PRIVATE);
    assertThat(Addresses.classify("orders.shop.svc.cluster.local")).isEqualTo(Host.CLUSTER);
    assertThat(Addresses.classify("orders.shop.svc")).isEqualTo(Host.CLUSTER);
    assertThat(Addresses.classify("order-service")).isEqualTo(Host.BARE);
    assertThat(Addresses.classify("172.32.0.1")).isEqualTo(Host.PUBLIC_IP);
    assertThat(Addresses.classify("203.0.113.7")).isEqualTo(Host.PUBLIC_IP);
    assertThat(Addresses.classify("api.paygate.example.com")).isEqualTo(Host.PUBLIC_DOMAIN);
  }

  @Test
  void substituteTakesTheLookupsValueBeforeTheDefaultAndKeepsWhatItCannotFill() {
    assertThat(
            LinkText.substitute(
                "${A:http://localhost:9}/x/${B}/${C:${D:d}}",
                k -> k.equals("A") ? "http://${H}:1" : k.equals("H") ? "web" : null))
        .isEqualTo("http://web:1/x/${B}/d");
  }
}
