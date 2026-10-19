use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
sub append { my ($p, $add, $blank) = @_; open(my $h, '<:raw', $p) or die $p; local $/; my $t = <$h>; close $h;
  my $crlf = $t =~ /\r\n/ ? 1 : 0; my $nl = $crlf ? "\r\n" : "\n"; (my $a = $add) =~ s/\n/$nl/g; $t =~ s/(\r?\n)*\z/$nl/;
  $t .= ($blank ? $nl : '') . $a; open($h, '>:raw', $p) or die; print $h $t; close $h; print "ok $p\n"; }
append("$root/docs/game-runs-2.md", <<'R', 1);
**hw216 DROPPED - hw216a hung on launch (2026-10-19 10:24-10:40).** `sbs-status.txt` stopped at "10:24:03 hw216a launching at 0,0"; the java process sat at 277 MB for 16 minutes and never logged "loaded" (a normal load is ~70 s; hw183a did the same on 2026-10-13). The watcher's 15-minute wait reported "hw216 never started"; driver, harness and game killed 10:40, the round not rerun (a failed clone means rerunning the whole round, facts "Never start a second sbs.ps1"). Instead `patch-n1b.pl` applied and the jar rebuilt 10:41 (siegeCanPay nets the yard hold off supplies as the launch does; a faction with its own noted hull shortfall lends none - "short itself" in the `Ally aid: no hulls` gate list), **hw217 (B) / hw218 (A) launched 10:42** by `run-siege-22.ps1`; hw218 covers set A on the N1 build. Read against hw215 70 / 91 / 189 (B) and hw212 137 / 79 / 83 (A): refused plays should be near 0, hull convoys far fewer than hw215a's 515.
R
append("$root/docs/facts.md", <<'F');
- **hw216a hung on launch (2026-10-19 10:24; second time after hw183a, 2026-10-13): the clone's java sat at 277 MB and never logged "loaded".** The watcher's "never started" exit (15 min without the game's log) is the signal; kill the driver, harness and java, then rerun or move on - never launch a second harness into the round. hw216 dropped; n1b built 10:41 and hw217 (B) / hw218 (A) launched 10:42. (game-runs-2.md s54)
F
print "done\n";
