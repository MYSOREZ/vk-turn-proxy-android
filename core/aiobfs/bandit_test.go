package aiobfs

import "testing"

func TestExp3ProbabilitiesSumToOne(t *testing.T) {
	e := newExp3(5, 0.15)
	p := e.probabilities()
	sum := 0.0
	for _, v := range p {
		sum += v
	}
	if sum < 0.999 || sum > 1.001 {
		t.Fatalf("probabilities should sum to ~1, got %f", sum)
	}
}

func TestExp3ConvergesToBestArm(t *testing.T) {
	const arms = 4
	const best = 2
	e := newExp3(arms, 0.1)

	for i := 0; i < 3000; i++ {
		arm, prob := e.selectArm()
		reward := 0.1
		if arm == best {
			reward = 1.0
		}
		e.update(arm, prob, reward)
	}

	w := e.snapshot()
	for i, wi := range w {
		if i != best && wi >= w[best] {
			t.Fatalf("expected best arm %d to have the highest weight after training, got weights %v", best, w)
		}
	}
}

func TestExp3NeverStarvesAnArm(t *testing.T) {
	// Even a permanently-losing arm should keep a nonzero selection
	// probability (the gamma exploration floor) — this is what lets the
	// shaper notice if a previously-bad disguise starts working again.
	e := newExp3(3, 0.2)
	for i := 0; i < 500; i++ {
		arm, prob := e.selectArm()
		reward := 0.0
		if arm == 0 {
			reward = 1.0
		}
		e.update(arm, prob, reward)
	}
	p := e.probabilities()
	for i, pi := range p {
		if pi <= 0 {
			t.Fatalf("arm %d has zero selection probability, exploration floor violated", i)
		}
	}
}

func TestSelectArmBiasedRespectsBiasDirection(t *testing.T) {
	e := newExp3(3, 0.1)
	// Strongly bias toward arm 1; with equal starting weights the biased
	// arm should be selected far more than a uniform 1/3 of the time.
	bias := []float64{-4, 4, -4}

	counts := make([]int, 3)
	const trials = 2000
	for i := 0; i < trials; i++ {
		arm, _, _ := e.selectArmBiased(bias)
		counts[arm]++
	}
	if counts[1] < trials/2 {
		t.Fatalf("expected heavily biased arm to dominate selection, got counts=%v", counts)
	}
}

func TestSelectArmBiasedDistributionSumsToOne(t *testing.T) {
	e := newExp3(4, 0.15)
	_, _, dist := e.selectArmBiased([]float64{1, -1, 0, 2})
	sum := 0.0
	for _, v := range dist {
		sum += v
	}
	if sum < 0.999 || sum > 1.001 {
		t.Fatalf("biased distribution should sum to ~1, got %f", sum)
	}
}
