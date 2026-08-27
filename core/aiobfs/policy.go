package aiobfs

import "math"

// policyNet is a tiny (5-8-N) feed-forward network trained online with
// REINFORCE (Williams, 1992) policy-gradient updates. It is the "AI" that
// decides how much to bias disguise selection given the *current* path
// conditions — the EXP3 bandit in bandit.go tracks "which arm has paid off
// lately" from scratch with no notion of *why*; this network additionally
// conditions on measured RTT/loss/throughput/dwell-time so it can, for
// example, learn to favor low-overhead audio-shaped packets when the link
// is clean and fast (helping throughput) and shift toward heavier,
// loss-tolerant video/screen-share shaped bursts when the link looks
// congested or actively interfered with.
//
// Deliberately small: 5 inputs, one 8-unit tanh hidden layer, N outputs.
// A forward+backward pass is a few dozen multiply-adds — microseconds, not
// milliseconds — so it can run on every packet without becoming the
// bottleneck it would be if this were an LLM call.
type policyNet struct {
	numIn     int
	numHidden int
	numOut    int

	wIn  [][]float64 // [hidden][in]
	bIn  []float64   // [hidden]
	wOut [][]float64 // [out][hidden]
	bOut []float64   // [out]

	lr           float64
	baseline     float64
	baselineRate float64

	rng *pseudoRand
}

// featureCount is the fixed input width: rttNorm, lossNorm, throughputNorm,
// dwellNorm, bias.
const featureCount = 5

func newPolicyNet(numOutputs int, learningRate float64) *policyNet {
	hidden := 8
	p := &policyNet{
		numIn:        featureCount,
		numHidden:    hidden,
		numOut:       numOutputs,
		wIn:          make([][]float64, hidden),
		bIn:          make([]float64, hidden),
		wOut:         make([][]float64, numOutputs),
		bOut:         make([]float64, numOutputs),
		lr:           learningRate,
		baselineRate: 0.05,
		rng:          newPseudoRand(),
	}
	for h := 0; h < hidden; h++ {
		p.wIn[h] = make([]float64, featureCount)
		for i := range p.wIn[h] {
			p.wIn[h][i] = p.rng.smallWeight()
		}
	}
	for o := 0; o < numOutputs; o++ {
		p.wOut[o] = make([]float64, hidden)
		for h := range p.wOut[o] {
			p.wOut[o][h] = p.rng.smallWeight()
		}
	}
	return p
}

// forward runs the network and returns (hidden activations, raw logits,
// output probabilities). hidden+probs are needed by train() for the
// backward pass; logits are handed to the bandit as a selection bias (see
// centeredBias in shaper.go) since exp() of a raw logit is a more natural
// log-weight than exp() of an already-normalized probability.
func (p *policyNet) forward(features []float64) (hidden, logits, probs []float64) {
	hidden = make([]float64, p.numHidden)
	for h := 0; h < p.numHidden; h++ {
		sum := p.bIn[h]
		for i := 0; i < p.numIn; i++ {
			sum += p.wIn[h][i] * features[i]
		}
		hidden[h] = math.Tanh(sum)
	}

	logits = make([]float64, p.numOut)
	for o := 0; o < p.numOut; o++ {
		sum := p.bOut[o]
		for h := 0; h < p.numHidden; h++ {
			sum += p.wOut[o][h] * hidden[h]
		}
		logits[o] = sum
	}
	probs = softmax(logits)
	return hidden, logits, probs
}

// train performs one REINFORCE update after observing `reward` (assumed in
// [0,1]) for having picked output index `chosen` given `features`, whose
// forward pass produced `hidden`/`probs`. It nudges weights so that action
// becomes more (or less) likely next time similar features are seen,
// scaled by the advantage over a running reward baseline.
func (p *policyNet) train(features, hidden, probs []float64, chosen int, reward float64) {
	advantage := reward - p.baseline
	p.baseline += p.baselineRate * (reward - p.baseline)

	// dL/dLogits for softmax + policy-gradient objective: (onehot - probs),
	// scaled by advantage (ascent direction).
	dLogits := make([]float64, p.numOut)
	for o := range dLogits {
		target := 0.0
		if o == chosen {
			target = 1.0
		}
		dLogits[o] = advantage * (target - probs[o])
	}

	// Backprop into output layer + accumulate gradient flowing into hidden.
	dHidden := make([]float64, p.numHidden)
	for o := 0; o < p.numOut; o++ {
		g := dLogits[o]
		p.bOut[o] += p.lr * g
		for h := 0; h < p.numHidden; h++ {
			dHidden[h] += g * p.wOut[o][h]
			p.wOut[o][h] += p.lr * g * hidden[h]
		}
	}

	// Backprop through tanh into input layer.
	for h := 0; h < p.numHidden; h++ {
		dPre := dHidden[h] * (1 - hidden[h]*hidden[h]) // tanh'(x) = 1-tanh(x)^2
		p.bIn[h] += p.lr * dPre
		for i := 0; i < p.numIn; i++ {
			p.wIn[h][i] += p.lr * dPre * features[i]
		}
	}
}

func softmax(logits []float64) []float64 {
	max := logits[0]
	for _, v := range logits[1:] {
		if v > max {
			max = v
		}
	}
	out := make([]float64, len(logits))
	sum := 0.0
	for i, v := range logits {
		e := math.Exp(v - max)
		out[i] = e
		sum += e
	}
	for i := range out {
		out[i] /= sum
	}
	return out
}
