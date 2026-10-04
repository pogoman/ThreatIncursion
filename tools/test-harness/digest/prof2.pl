# prof2.pl <files>: the mod's share of the main thread and which of its calls it is in (call path from IncursionManager.advance down)
my (%p2, %p3, %inner, %van, $n, $inMod);
for my $file (@ARGV) { local $/; open my $f, '<', $file or next; my $t = <$f>;
  for my $blk (split /\n\n/, $t) { next unless $blk =~ /^"Thread-2"/ && $blk =~ /CampaignEngine\.advance/;
    my @fr = $blk =~ /^\s+at (\S+?)\(/mg; next unless @fr; $n++;
    my @m = reverse grep { /^threatinc\./ } @fr;   # outermost first
    if (@m) { $inMod++; s/^threatinc\.// for @m; $p2{$m[1] // '(advance itself)'}++; $p3{join(' > ', grep { defined } @m[1, 2])}++ if @m > 2; $inner{$m[-1]}++ }
    else { my ($v) = grep { /^com\.fs\.starfarer\.(api\.impl\.campaign|campaign)\.[A-Za-z.]+\.(advance|advanceImpl)$/ } @fr; $v //= $fr[0]; $v =~ s/^com\.fs\.starfarer\.//; $van{$v}++ }
    last } }
printf "%d samples, %d%% in the mod\n", $n, 100 * $inMod / ($n || 1);
my $show = sub { my ($h, $title, $k) = @_; print "-- $title\n"; my $i = 0; for (sort { $h->{$b} <=> $h->{$a} } keys %$h) { printf "  %4.1f%%  %s\n", 100 * $h->{$_} / $n, $_; last if ++$i >= $k } };
$show->(\%p2, "mod: called from IncursionManager.advance", 14);
$show->(\%p3, "mod: two levels down", 14);
$show->(\%inner, "mod: innermost frame", 14);
$show->(\%van, "vanilla: innermost advance", 8);
