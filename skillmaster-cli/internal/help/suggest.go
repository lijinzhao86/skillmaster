package help

// Suggest returns the same-level name closest to word, when exactly one name is that close. Two
// things are deliberately not suggestions: a word nothing is near (a typo too far to guess), and a
// word several names are equally near (a guess would be a coin flip). The caller prints its parent's
// help address in both cases.
//
// The bound is 2 because that is what catches the misspellings people actually make — one wrong key
// (`invoek`), one missing letter (`invok`), one transposition (`--jsno`) — without reaching across
// words that merely share a prefix.
func Suggest(word string, candidates []string) (string, bool) {
	best := ""
	bestDistance := 3
	tied := false
	for _, candidate := range candidates {
		distance := editDistance(word, candidate)
		switch {
		case distance < bestDistance:
			best, bestDistance, tied = candidate, distance, false
		case distance == bestDistance:
			tied = true
		}
	}
	if bestDistance > 2 || tied {
		return "", false
	}
	return best, true
}

// editDistance is Levenshtein distance over runes, so a Chinese word costs the same per character
// as an ASCII one.
func editDistance(a, b string) int {
	ra, rb := []rune(a), []rune(b)
	previous := make([]int, len(rb)+1)
	current := make([]int, len(rb)+1)
	for j := range previous {
		previous[j] = j
	}
	for i := 1; i <= len(ra); i++ {
		current[0] = i
		for j := 1; j <= len(rb); j++ {
			cost := 1
			if ra[i-1] == rb[j-1] {
				cost = 0
			}
			current[j] = min3(current[j-1]+1, previous[j]+1, previous[j-1]+cost)
		}
		previous, current = current, previous
	}
	return previous[len(rb)]
}

func min3(a, b, c int) int {
	minimum := a
	if b < minimum {
		minimum = b
	}
	if c < minimum {
		minimum = c
	}
	return minimum
}
