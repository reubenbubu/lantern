# The image of Investigate's shell (#893, ADR 0127): read-only text tools over a read-only copy of
# the repositories, no network, no git, no package manager, nothing to compile or run code with.
# Lantern tags it by a hash of this file and builds it the first time it is needed.

FROM alpine@sha256:87a4361f6c819618a6bafa2ed5228714cb0bb22195d2b49c95eb26799990128a AS yq
# mikefarah's yq, one pinned release, checked against its published SHA-256.
ARG YQ_VERSION=v4.44.6
ARG YQ_SHA256=0c2b24e645b57d8e7c0566d18643a6d4f5580feeea3878127354a46f2a1e4598
RUN wget -q -O /yq "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64" \
 && echo "${YQ_SHA256}  /yq" | sha256sum -c - \
 && chmod 0755 /yq

FROM alpine@sha256:87a4361f6c819618a6bafa2ed5228714cb0bb22195d2b49c95eb26799990128a
RUN apk add --no-cache coreutils findutils grep ripgrep sed gawk jq libxml2-utils tree diffutils file \
 # No package manager left to use, and no busybox applet that reaches a network or edits files in place.
 && rm -rf /sbin/apk /etc/apk /lib/apk /usr/share/apk /var/cache/apk \
 && for applet in wget nc telnet ftpget ftpput tftp httpd ssl_client nslookup ping ping6 traceroute \
      traceroute6 ifconfig ip route udhcpc ntpd whois sendmail vi ed patch; do \
      rm -f "/bin/$applet" "/sbin/$applet" "/usr/bin/$applet" "/usr/sbin/$applet"; \
    done
COPY --from=yq /yq /usr/local/bin/yq
WORKDIR /work
USER 65534:65534
