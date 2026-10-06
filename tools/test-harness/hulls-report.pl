#!/usr/bin/perl
# The hull pool over a run, from its monthly dumps (docs/hull-pool.md):
#   perl tools/test-harness/hulls-report.pl <dir with threatinc_simdump_*.json.data> [every N months]
# One line a month: war day, hives, swarm fleets, then per mobilised faction
# standing / out / lost / free / yards. Reads the dump's factions[] entries.
use strict;
use warnings;
use JSON::PP;

my $dir = shift or die "usage: hulls-report.pl <dumpdir> [every]\n";
my $every = shift || 1;
opendir(my $dh, $dir) or die "$dir: $!";
my @files = sort { ($a =~ /d(-?\d+)/)[0] <=> ($b =~ /d(-?\d+)/)[0] }
	grep { /^threatinc_simdump_d-?\d+\.json(\.data)?$/ } readdir($dh);
closedir $dh;
my $n = 0;
for my $f (@files) {
	next if $n++ % $every;
	open(my $fh, '<', "$dir/$f") or next;
	local $/;
	my $d = eval { decode_json(<$fh>) };
	close $fh;
	next unless $d;
	my $hives = ref $d->{hives} eq 'ARRAY' ? scalar @{$d->{hives}} : '?';
	my $fleets = 0;
	if (ref $d->{hives} eq 'ARRAY') { $fleets += $_->{garrisonFP} || 0 for @{$d->{hives}} }
	my @parts;
	for my $fa (@{$d->{factions} || []}) {
		next unless defined $fa->{hulls};
		push @parts, sprintf("%s %d/%d/%d/%d y%d", $fa->{id}, $fa->{hulls}, $fa->{hullsOut},
			$fa->{hullsLost}, $fa->{hullsFree}, $fa->{hullYards});
	}
	printf "d%-5d hives %-3s garrisons %-6d %s\n", $d->{warDay} || 0, $hives, $fleets,
		@parts ? join("; ", @parts) : "(none mobilised)";
}
