# Convex Devnet Deployer

Tools to launch a convex network on AWS as a cloudformation stack.

See comments in [deploy.clj](deploy/deploy.clj) instructions for deploying a network. You will need to clone this repo and run a repl with the `:dev` alias (`clj -M:dev`) from that directory to run.

See example at the bottom of [peer.clj](src/peer.clj) for example benchmarking a local network.

I use the term "Friend" to describe the initial peer which creates the genesis state and which funds the subsequent peers and creates their initial account.

# Todo 
* [ ] Cross-region networks
* [ ] Better non-single-segment namespace names.
* [ ] CLI interface for deploying
* [ ] More parameters for cloudformation: Instance type, etc.
