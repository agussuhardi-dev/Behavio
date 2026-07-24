import { Component, ViewEncapsulation, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'error-code',
  templateUrl: './error-code.html',
  styleUrl: './error-code.scss',
  encapsulation: ViewEncapsulation.None,
  imports: [RouterLink, MatButtonModule, TranslatePipe],
})
export class ErrorCode {
  readonly code = input('');
  readonly title = input('');
  readonly message = input('');
}
