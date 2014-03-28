/*
Enable control of a SynthTask's synth parameters from multiple sources: 
Views, patterns, busses, MIDIFuncs, OSCFuncs, etc.

Each source is stored under a name in a dictionary (controls).  
It can be individually enabled/disabled, changed or removed. 

IZ Tue, Mar 11 2014, 18:10 EET
*/

SynthTreeArgs : /* IdentityDictionary */ Event {
	var <synthTree;
	var <event;

	*new { | synthTree | ^super.new.init(synthTree); }

	init { | argSynthTree |
		synthTree = argSynthTree;
	}

	storeArgValue { | key, value |
		this.getParam(key).storeValue(value);
	}

	getParam { | key, spec, initialValue, stream |
		var param;
		param = this[key];
		param ?? {
			param = MultiControl(synthTree, key, spec, initialValue, stream);
			this[key] = param;
		};
		^param;
	}

	makeParam { | key, spec |
		[this, thisMethod.name, "not yet implemented"].postln;
	}
}

MultiControl : IdentityDictionary {
	var <synthTree; // SynthTree to which I belong
	var <name;     // name of synth parameter to control
	var <>spec;    // spec to map incoming values from controls
	var <>stream;  // if not nil, provides next value instead of nextValue var.
	var <>nextValue; // next value to use for that parameter.
	// control objects are stored in self as subclass of IdentityDictionary
	// var <>controls; // dictionary holding any control objects 
	//	(MIDIFuncs, OSCFuncs, ViewFuncs, Busses etc.)
	/* Note: Other sources, such as: 
		Patterns, StreamPatterns and PatternPlayers should be stored globally
		each in its own dict, and added to any number of SynthTrees.
		One SynthTree might want to compose the stream source
		used by another SynthTree with a second stream source!
	*/
	var unmappedValue; // cache of unmappedValue for views
	
	*new { | synthTree, name, spec, initialValue, stream |
		^super.new.init(synthTree, name, spec, initialValue, stream);
	}

	init { | argSynthTree, argName, argSpec, initialValue, argStream |
		synthTree = argSynthTree;
		name = argName;
		spec = (argSpec ? name).asSpec ? NullSpec;
		stream = argStream.asStream;
		nextValue = initialValue ?? { stream.next ?? { spec.default } };
	}

	synthArgs {
		/* Return name + next value, for constructing Synth args. */
		^[name, this.next];
	}

	next { 
		/*  Called when starting the synth to get arg parameters.
			If there is a stream, get the value from the stream.
			Otherwise get the stored value.
		*/
		if (stream.notNil) {
			nextValue = stream.next;
			^nextValue;
		}{
			^nextValue;
		}
	}

	mapSet { | value |
		// map the value received from MIDI or view etc. from 0-1 range 
		// to desired range
		this.set(spec map: value, value);
	}

	set { | value, argUnmappedValue |
		synthTree.setSynthParameter(name, value);
		this.storeValue(value, argUnmappedValue);
	}

	storeValue { | value, argUnmappedValue |
		nextValue = value;
		unmappedValue = argUnmappedValue ?? {
			spec unmap: value;
		};
		this.changed(\value, value, unmappedValue);
	}

	// NOT YET DONE:
	map { | curve |
		/*  Fade any parameter to any value(s) using a line or envelope ugen
			on a control bus, mapped to the parameter.
			The control bus is allocated on the fly and released when the 
			fade synth is freed.  Specification of curves: 
			target@dur point: line from current value to x in dur seconds.
			function: make control rate output synth to the bus.
			Env: Play it with control rate synth to the bus.
			
			Enhancements: Add value of param at start as offset to control signal.
			Control shape funcs/envs etc. can be stored in separate lists/symbols and
			mapped to params by \curve =@>.param \synth
			Or by drag-and-drop from curve list GUI to knobs of synth gui.
			var synthFunc, bus, synth, startVal, endVal;

			Implementation Note: 
			This should be handled by a separate class encapsulating the bus
			to be mapped and any number of synths that are writing to it.

		*/
		var bus, synthFunc, synth, startVal, endVal;
		bus = Bus.control(this.server, 1);

		synth = synthFunc.();
		synth.onEnd({
			
			this.set()
		})
	}

	add { | controlName, control |

	}

	get { | controlName |

	}

	remove { | controlName |
		// remove a control.  Disable it first
		var ctl;
		ctl = this[controlName];
		if (ctl.notNil) {
			ctl.disable;
			this[controlName] = nil;
		};
	}

	enable { | controlName, start = false |
		// enable a control to send me messages
		// If start is true, then also start a control's process

	}

	disable { | controlName, stop = false |
		// disable a control to send me messages
		// If stop is true, then also stop a control's process

	}

	start { | controlName |
		// start a control's process, if appropriate
		// works for StreamPatterns and Synths
	}

	stop { | controlName |
		// stop a control's process, if appropriate
	}

	reset { | controlName, start = false |
		// reset a control's process, if appropriate
		// If start is true, then also start a control's process

	}

	// adding specific kinds of objects
	addMIDI { | name, spec, func |
		
	}

	addOSC { | name, path, func |
		
	}

	addView { | argName, view, func, onClose, enabled = true |
		argName = argName ? name;
		// view = view ?? { Knobs.knob(argName, synthTree.name) };
		this[argName] = ViewFunc(
			this, // only one ViewFunc is added per argName
			view ?? {
				this.connectParamView(
					Knobs.knob(argName, synthTree.name)
				)
			},
			func ?? {{ | value | this.set(spec.map(value)) }},
			onClose ?? {{ this.remove(argName) }},
			enabled
		).value_(spec unmap: nextValue);
	}

	connectParamView { | view |
		view.addNotifier(synthTree, \started, { | ... args |
			view.setPlaying;
		});
		view.addNotifier(synthTree, \stopped, { | ... args |
			if (synthTree.isPlaying) {} { view.setStopped; };
		});
		if (synthTree.isPlaying) { 
			view.setPlaying;
		};
		view.keyDownAction = { | view, char, modifiers, unicode, keycode, key |
			switch (char,
				$g, { synthTree.start },
				$G, { synthTree.trig },
				$s, { synthTree.fadeOut },
				$S, { synthTree.free },
				$k, { synthTree.knobs },
				$b, { synthTree.bufferList },
				$,, { thisProcess.stop },
				$., { SynthTree.stopAll },
				$i, { SynthTree.initTree },
				$/, { SynthTree.initTree },
				Char.space, { synthTree.toggle },
				{ view.defaultKeyDownAction(
					char, modifiers, unicode, keycode, key) 
				}
			)
		};
		view.addNotifier(this, \value, { | value, unmappedValue |
			{ view.value = unmappedValue; }.defer;
		});
		view.value = unmappedValue ?? { spec unmap: nextValue };
		^view;
	}
	// NOT YET TESTED!
	setBuffer { | bufName, action |		
		/* Different handling:
			buffer multicontrol instances only have a single item in their dict,
			which is a BufferFunc. 
			Setting a new buffer disconnects the previous one. 
		*/
		var bufferFunc;
		bufName = bufName ? name;
		bufferFunc = this[\buffer];
		if (bufferFunc.notNil) { bufferFunc.objectClosed };
		bufferFunc = BufferFunc(synthTree.server,
			bufName,
			action ?? {{ | argBufferFunc | this.set(argBufferFunc.bufnum); }},
		);
		this[\buffer] = bufferFunc;
		this.set(bufferFunc.bufnum);
	}

	addSynth { | name, template |

	}

	addBus { | name, bus |
		
	}

	//
	senderClosed { | sender |
		// find sender from values of dict and remove it

	}
}