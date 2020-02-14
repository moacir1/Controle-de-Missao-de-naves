import { Component, Input } from '@angular/core';
import { Alarm } from '../client';

@Component({
  selector: 'app-alarm-state-icon',
  templateUrl: './AlarmStateIcon.html',
})
export class AlarmStateIcon {

  @Input()
  alarm: Alarm;
}