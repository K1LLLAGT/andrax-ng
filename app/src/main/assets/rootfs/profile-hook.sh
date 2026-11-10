# ANDRAX-NG: runs the first-login cleanup once, then deletes itself.
if [ "$(id -u)" = "0" ] && [ -x /usr/local/sbin/andrax-ng-firstboot ]; then
  /usr/local/sbin/andrax-ng-firstboot
  rm -f /etc/profile.d/10-andrax-ng-firstboot.sh
fi
