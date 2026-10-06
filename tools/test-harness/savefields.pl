# usage: perl savefields.pl campaign.xml  -> "class<TAB>field" lines, unique
local $/; open F, "<:raw", $ARGV[0] or die; $_ = <F>; close F;
my @stack; my %seen; my %alias;
# XStream aliases: an element name may itself be a class
while (m{<([^\s/>!?]+)([^>]*?)(/?)>|</([^\s>]+)>}g) {
  if (defined $4) { pop @stack; next; }
  my ($tag, $attrs, $selfclose) = ($1, $2, $3);
  my $cls;
  if ($attrs =~ /\bclass="([^"]+)"/) { $cls = $1 } elsif ($tag =~ /^threatinc\./) { $cls = $tag }
  if (@stack && $stack[-1][0]) {
    # a child of a threatinc instance: a field
    $seen{$stack[-1][0]}{$tag} = 1;
  }
  my $rec = ($cls && $cls =~ /^threatinc\./) ? $cls : undef;
  $rec =~ s/_-/\$/g if $rec;
  push @stack, [$rec] unless $selfclose;
  if ($selfclose && $rec) { $seen{$rec} ||= {}; }
}
for my $c (sort keys %seen) { for my $f (sort keys %{$seen{$c}}) { print "$c\t$f\n" } }
