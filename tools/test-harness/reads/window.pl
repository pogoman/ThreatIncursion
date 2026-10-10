#!/usr/bin/perl
# window.pl <ti-tag.txt> <from war day> <to> <regex> [max lines] - the matching lines in a war-day window, day prefixed
# print lines matching a pattern within a war-day window, with the day prefixed
my ($f,$from,$to,$pat,$max)=@ARGV; $max||=40; my $day=0; my $n=0;
open my $h,'<',$f or die;
while(<$h>){ if(/^Clock: day \S+ war (\d+)/){$day=$1;next} next if $day<$from; last if $day>$to;
  if(/$pat/){ print "d$day ", substr($_,0,330); last if ++$n>=$max } }
