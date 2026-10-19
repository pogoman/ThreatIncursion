#!/usr/bin/perl
# 2026-10-19 12:50: hw217b / hw218b / hw219b all "NO GAME WINDOW" at the second slot (the launcher up, Play never
# took, 180 s) - a game a round lost. sbs.ps1 now queues a tag that fails to launch (NO PROCESS / NO GAME WINDOW /
# NOT LOADED) once more after the others; the per-tag body resets the clone, logs and tailer, so a rerun is clean.
use strict; use warnings;
my $root = 'C:/Program Files (x86)/Fractal Softworks/Starsector/mods/ThreatIncursion';
sub patch {
    my ($file, @pairs) = @_;
    my $path = "$root/$file";
    open(my $fh, '<:raw', $path) or die "open $path: $!";
    local $/; my $text = <$fh>; close $fh;
    my $crlf = ($text =~ /\r\n/) ? 1 : 0;
    $text =~ s/\r\n/\n/g if $crlf;
    while (@pairs) {
        my ($from, $to) = splice(@pairs, 0, 2);
        my $count = () = $text =~ /\Q$from\E/g;
        die "$file: expected 1 match, got $count for:\n$from\n" unless $count == 1;
        $text =~ s/\Q$from\E/$to/;
    }
    $text =~ s/\n/\r\n/g if $crlf;
    open($fh, '>:raw', $path) or die "write $path: $!";
    print $fh $text; close $fh;
    print "patched $file (crlf=$crlf)\n";
}

patch('tools/test-harness/fastforward/sbs.ps1',
"\$g = @()\n\$i = 0\nforeach (\$tag in \$Tags) {\n",
"\$g = @()\n\$i = 0\n# a tag whose launch fails (no process, the launcher's Play never taken, Continue missed) goes to the back of the\n# queue once (2026-10-19: the second slot lost its window three rounds running); the body resets its clone, logs\n# and tailer, so the rerun starts clean\n\$queue = New-Object System.Collections.Generic.List[string]; foreach (\$t in \$Tags) { \$queue.Add(\$t) }\n\$retried = \@{}\nfunction Requeue(\$t) {\n  if (\$retried[\$t]) { return }\n  \$retried[\$t] = \$true\n  \"stop\" | Set-Content \"\$d\\tail-\$t.stop\"; Start-Sleep 10\n  \$queue.Add(\$t); Say \"\$t relaunched after the others\"\n}\nwhile (\$queue.Count -gt 0) {\n  \$tag = \$queue[0]; \$queue.RemoveAt(0)\n",
"  if (-not \$procId) { Say \"\$tag NO PROCESS\"; continue }\n",
"  if (-not \$procId) { Say \"\$tag NO PROCESS\"; Requeue \$tag; continue }\n",
"  if (\$r -notlike \"*client \$Resolution*\") { Say \"\$tag NO GAME WINDOW (\$r)\"; Stop-Process -Id \$procId -Force -ErrorAction SilentlyContinue; continue }\n",
"  if (\$r -notlike \"*client \$Resolution*\") { Say \"\$tag NO GAME WINDOW (\$r)\"; Stop-Process -Id \$procId -Force -ErrorAction SilentlyContinue; Requeue \$tag; continue }\n",
"  if (-not \$loaded) { Say \"\$tag NOT LOADED (Continue missed, or the save was not found)\"; Stop-Process -Id \$procId -Force -ErrorAction SilentlyContinue; continue }\n",
"  if (-not \$loaded) { Say \"\$tag NOT LOADED (Continue missed, or the save was not found)\"; Stop-Process -Id \$procId -Force -ErrorAction SilentlyContinue; Requeue \$tag; continue }\n");
print "all patched\n";
