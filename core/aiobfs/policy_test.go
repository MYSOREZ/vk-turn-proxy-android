package aiobfs

import "testing"

func TestSoftmaxSumsToOneAndIsMonotonic(t *testing.T) {
	p := softmax([]float64{0, 1, 2})
	sum := 0.0
	for _, v := range p {
		sum += v
	}
	if sum < 0.999 || sum > 1.001 {
		t.Fatalf("softmax should sum to ~1, got %f", sum)
	}
	if !(p[0] < p[1] && p[1] < p[2]) {
		t.Fatalf("softmax should be monotonic in its inputs, got %v", p)
	}
}

func TestPolicyNetForwardOutputsValidDistribution(t *testing.T) {
	net := newPolicyNet(4, 0.05)
	features := []float64{0.5, 0.1, 0.8, 0.2, 1.0}
	_, _, probs := net.forward(features)
	if len(probs) != 4 {
		t.Fatalf("expected 4 outputs, got %d", len(probs))
	}
	sum := 0.0
	for _, v := range probs {
		if v < 0 {
			t.Fatalf("negative probability %f", v)
		}
		sum += v
	}
	if sum < 0.999 || sum > 1.001 {
		t.Fatalf("probs should sum to ~1, got %f", sum)
	}
}

func TestPolicyNetLearnsToPreferRewardedAction(t *testing.T) {
	net := newPolicyNet(3, 0.2)
	features := []float64{0.3, 0.3, 0.3, 0.3, 1.0}

	_, _, before := net.forward(features)

	for i := 0; i < 300; i++ {
		hidden, _, probs := net.forward(features)
		// Always reward action 0 for this fixed feature vector, never
		// reward the others — after training, action 0's probability
		// mass for this exact input should have grown.
		net.train(features, hidden, probs, 0, 1.0)
	}

	_, _, after := net.forward(features)
	if after[0] <= before[0] {
		t.Fatalf("expected action 0's probability to increase after consistent reward, before=%v after=%v", before, after)
	}
	for i := 1; i < len(after); i++ {
		if after[0] <= after[i] {
			t.Fatalf("expected action 0 to dominate after training, got %v", after)
		}
	}
}
